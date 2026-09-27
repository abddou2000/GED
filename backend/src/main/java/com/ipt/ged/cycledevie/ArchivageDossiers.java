package com.ipt.ged.cycledevie;

import com.ipt.ged.document.evenement.Acteur;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.management.ManagementFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Archivage d'un <b>dossier entier</b> en une fois (revue client D10, §12.6
 * « archivage d'un ensemble volumineux ») : action manuelle, jamais
 * automatique.
 *
 * <p>La demande pose le drapeau d'archivage sur le dossier (plus aucun dépôt,
 * question Q7) et crée un {@code job_archivage} avec sa sélection figée
 * (documents actifs du dossier et de ses sous-dossiers). Un travailleur le
 * traite par tranches de 100 documents, chacune dans sa transaction ; bail
 * prolongé à chaque tranche (reprise par une autre instance après
 * interruption) ; progression lisible à tout moment ; annulation prise en
 * compte entre deux tranches (les documents déjà archivés le restent, le
 * drapeau du dossier est retiré) ; rapport final par document ; un événement
 * d'audit {@code DOCUMENT_ARCHIVE} par document archivé.
 */
@Service
public class ArchivageDossiers {

    private static final Logger log = LoggerFactory.getLogger(ArchivageDossiers.class);
    private static final String INSTANCE = ManagementFactory.getRuntimeMXBean().getName();

    private final JdbcTemplate jdbc;
    private final Dossiers dossiers;
    private final AutorisationsCycleDeVie autorisations;
    private final ArchivageService archivage;
    private final ApplicationEventPublisher evenements;
    private final ProprietesCycleDeVie proprietes;
    private final TransactionTemplate transaction;

    public ArchivageDossiers(JdbcTemplate jdbc, Dossiers dossiers, AutorisationsCycleDeVie autorisations,
                             ArchivageService archivage, ApplicationEventPublisher evenements,
                             ProprietesCycleDeVie proprietes, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.dossiers = dossiers;
        this.autorisations = autorisations;
        this.archivage = archivage;
        this.evenements = evenements;
        this.proprietes = proprietes;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** État d'un job, pour le suivi de progression. */
    public record Job(UUID id, UUID dossierId, String dossierNom, UUID demandeurId, String etat, int total,
                      int traites, int archives, int anomalies, int echecs, boolean annulationDemandee,
                      Instant creeLe, Instant demarreLe, Instant termineLe) {
    }

    /** Ligne du rapport final. */
    public record Element(UUID documentId, String nom, int rang, String resultat, String motif, Instant traiteLe) {
    }

    private static final RowMapper<Job> JOB = (rs, i) -> new Job(rs.getObject("id", UUID.class),
            rs.getObject("dossier_id", UUID.class), rs.getString("dossier_nom"),
            rs.getObject("demandeur_employe_id", UUID.class), rs.getString("etat"), rs.getInt("total"),
            rs.getInt("traites"), rs.getInt("archives"), rs.getInt("anomalies"), rs.getInt("echecs"),
            rs.getBoolean("annulation_demandee"), instant(rs.getTimestamp("cree_le")),
            instant(rs.getTimestamp("demarre_le")), instant(rs.getTimestamp("termine_le")));

    /* ======================= Demandes ======================= */

    /** Demande l'archivage d'un dossier entier : drapeau posé, job créé (202). */
    public Job archiverDossier(UUID dossierId) {
        Dossiers.Dossier dossier = dossiers.trouver(dossierId)
                .orElseThrow(() -> ErreurCycleDeVie.introuvable("Dossier " + dossierId));
        if (!autorisations.peutArchiverDossier(dossierId)) throw ErreurCycleDeVie.permission("Archiver");
        Acteur acteur = Acteur.courant();
        UUID jobId = transaction.execute(s -> {
            // Sérialise les demandes concurrentes sur le même dossier.
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "archivage:" + dossierId);
            Integer enCours = jdbc.queryForObject("SELECT count(*) FROM job_archivage WHERE dossier_id = ? "
                    + "AND etat IN ('EN_ATTENTE', 'EN_COURS')", Integer.class, dossierId);
            if (enCours != null && enCours > 0) {
                throw ErreurCycleDeVie.conflit(ErreurCycleDeVie.ARCHIVAGE_EN_COURS,
                        "Un archivage de ce dossier est déjà en cours.");
            }
            List<UUID> selection = new ArrayList<>();
            List<UUID> candidats = dossiers.documents(dossierId).stream().map(Dossiers.DocumentRange::documentId).toList();
            if (!candidats.isEmpty()) {
                selection.addAll(jdbc.queryForList("SELECT id FROM document WHERE id = ANY(?) AND statut_conservation = 'ACTIF'",
                        UUID.class, (Object) candidats.toArray(new UUID[0])));
                // Ordre du dossier conservé.
                selection.sort(java.util.Comparator.comparingInt(candidats::indexOf));
            }
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO job_archivage (id, dossier_id, dossier_nom, demandeur_employe_id, etat, total) "
                    + "VALUES (?, ?, ?, ?, 'EN_ATTENTE', ?)", id, dossierId, dossier.nom(), acteur.employeId(), selection.size());
            List<Object[]> lignes = new ArrayList<>();
            for (int i = 0; i < selection.size(); i++) lignes.add(new Object[]{id, selection.get(i), i});
            jdbc.batchUpdate("INSERT INTO job_archivage_element (job_archivage_id, document_id, rang) VALUES (?, ?, ?)", lignes);
            dossiers.marquerArchive(dossierId, true);
            evenements.publishEvent(EvenementDossier.archivage(dossierId, dossier.nom(), acteur, id, selection.size()));
            return id;
        });
        return job(jobId).orElseThrow();
    }

    /**
     * Demande l'annulation : prise en compte entre deux tranches ; un job pas
     * encore commencé est annulé sur-le-champ. 409 s'il est déjà terminé.
     */
    public Job annuler(UUID jobId) {
        Job j = job(jobId).orElseThrow(() -> ErreurCycleDeVie.introuvable("Job d'archivage " + jobId));
        if (!autorisations.peutArchiverDossier(j.dossierId())) throw ErreurCycleDeVie.permission("Archiver");
        transaction.executeWithoutResult(s -> {
            int n = jdbc.update("UPDATE job_archivage SET annulation_demandee = true WHERE id = ? "
                    + "AND etat IN ('EN_ATTENTE', 'EN_COURS')", jobId);
            if (n == 0) {
                throw ErreurCycleDeVie.conflit(ErreurCycleDeVie.JOB_TERMINE, "Ce job d'archivage est déjà terminé.");
            }
            int annule = jdbc.update("UPDATE job_archivage SET etat = 'ANNULE', termine_le = now() "
                    + "WHERE id = ? AND etat = 'EN_ATTENTE'", jobId);
            if (annule == 1) dossiers.marquerArchive(j.dossierId(), false);
        });
        return job(jobId).orElseThrow();
    }

    /**
     * Retire le drapeau d'archivage d'un dossier (dépôts de nouveau acceptés) ;
     * ses documents restent archivés, leur désarchivage se fait un par un.
     */
    public void retirerDrapeau(UUID dossierId) {
        Dossiers.Dossier dossier = dossiers.trouver(dossierId)
                .orElseThrow(() -> ErreurCycleDeVie.introuvable("Dossier " + dossierId));
        if (!autorisations.peutArchiverDossier(dossierId)) throw ErreurCycleDeVie.permission("Archiver");
        transaction.executeWithoutResult(s -> {
            Integer enCours = jdbc.queryForObject("SELECT count(*) FROM job_archivage WHERE dossier_id = ? "
                    + "AND etat IN ('EN_ATTENTE', 'EN_COURS')", Integer.class, dossierId);
            if (enCours != null && enCours > 0) {
                throw ErreurCycleDeVie.conflit(ErreurCycleDeVie.ARCHIVAGE_EN_COURS,
                        "Un archivage de ce dossier est en cours : l'annuler d'abord.");
            }
            dossiers.marquerArchive(dossierId, false);
            evenements.publishEvent(EvenementDossier.desarchivage(dossierId, dossier.nom(), Acteur.courant()));
        });
    }

    public Optional<Job> job(UUID jobId) {
        return jdbc.query("SELECT * FROM job_archivage WHERE id = ?", JOB, jobId).stream().findFirst();
    }

    /** Jobs récents, d'un dossier ou de tous. */
    public List<Job> jobs(UUID dossierId, int limite) {
        int n = Math.max(1, Math.min(limite, 200));
        return dossierId != null
                ? jdbc.query("SELECT * FROM job_archivage WHERE dossier_id = ? ORDER BY cree_le DESC LIMIT " + n, JOB, dossierId)
                : jdbc.query("SELECT * FROM job_archivage ORDER BY cree_le DESC LIMIT " + n, JOB);
    }

    /** Rapport : éléments du job, éventuellement filtrés par résultat ({@code EN_ATTENTE} = non traités). */
    public List<Element> elements(UUID jobId, String resultat, int page, int taille) {
        int t = Math.max(1, Math.min(taille, 200));
        int decalage = Math.max(0, page) * t;
        String filtre = resultat == null || resultat.isBlank() ? ""
                : "EN_ATTENTE".equals(resultat) ? " AND e.resultat IS NULL" : " AND e.resultat = ?";
        List<Object> params = new ArrayList<>(List.of(jobId));
        if (!filtre.isEmpty() && !"EN_ATTENTE".equals(resultat)) params.add(resultat);
        return jdbc.query("SELECT e.document_id, d.name, e.rang, e.resultat, e.motif, e.traite_le "
                        + "FROM job_archivage_element e JOIN document d ON d.id = e.document_id WHERE e.job_archivage_id = ?" + filtre
                        + " ORDER BY e.rang LIMIT " + t + " OFFSET " + decalage,
                (rs, i) -> new Element(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getString(4),
                        rs.getString(5), instant(rs.getTimestamp(6))), params.toArray());
    }

    /* ======================= Travailleur ======================= */

    /**
     * Réserve un job (en attente, ou en cours dont le bail a expiré : reprise
     * après interruption) et le traite jusqu'au bout, tranche par tranche.
     *
     * @return {@code true} si un job a été traité.
     */
    public boolean traiterUnJob() {
        List<Job> reserve = transaction.execute(s -> jdbc.query("""
                UPDATE job_archivage SET etat = 'EN_COURS', verrouille_par = ?, verrouille_jusqu_a = ?,
                       demarre_le = coalesce(demarre_le, now())
                 WHERE id = (SELECT id FROM job_archivage
                              WHERE etat IN ('EN_ATTENTE', 'EN_COURS')
                                AND (verrouille_jusqu_a IS NULL OR verrouille_jusqu_a < now())
                              ORDER BY cree_le FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING *
                """, JOB, INSTANCE, bail()));
        if (reserve == null || reserve.isEmpty()) return false;
        Job job = reserve.get(0);
        Acteur acteur = new Acteur(job.demandeurId(), null);
        while (true) {
            Boolean annulation = jdbc.queryForObject("SELECT annulation_demandee FROM job_archivage WHERE id = ?",
                    Boolean.class, job.id());
            if (Boolean.TRUE.equals(annulation)) {
                transaction.executeWithoutResult(s -> {
                    jdbc.update("UPDATE job_archivage SET etat = 'ANNULE', termine_le = now(), verrouille_par = NULL, "
                            + "verrouille_jusqu_a = NULL WHERE id = ?", job.id());
                    dossiers.marquerArchive(job.dossierId(), false);
                });
                log.info("Archivage du dossier {} annulé (job {})", job.dossierId(), job.id());
                return true;
            }
            List<UUID> tranche = jdbc.queryForList("SELECT document_id FROM job_archivage_element WHERE job_archivage_id = ? "
                    + "AND resultat IS NULL ORDER BY rang LIMIT " + proprietes.getArchivage().getTranche(), UUID.class, job.id());
            if (tranche.isEmpty()) {
                jdbc.update("UPDATE job_archivage SET etat = 'TERMINE', termine_le = now(), verrouille_par = NULL, "
                        + "verrouille_jusqu_a = NULL WHERE id = ?", job.id());
                log.info("Archivage du dossier {} terminé (job {})", job.dossierId(), job.id());
                return true;
            }
            traiterTranche(job, acteur, tranche);
        }
    }

    /**
     * Une tranche : préparation document par document (hors transaction), puis
     * une transaction pour toute la tranche. Si elle échoue, repli document par
     * document, pour qu'un document fautif ne bloque pas le job indéfiniment.
     */
    private void traiterTranche(Job job, Acteur acteur, List<UUID> tranche) {
        List<ArchivageService.Preparation> preparations = new ArrayList<>();
        for (UUID doc : tranche) {
            try {
                preparations.add(archivage.preparer(doc));
            } catch (RuntimeException e) {
                log.warn("Archivage : préparation du document {} en échec", doc, e);
                preparations.add(new ArchivageService.Preparation(doc, null, null, null, null,
                        new ArchivageService.Rejet(ArchivageService.Issue.ECHEC, "ERREUR", message(e))));
            }
        }
        try {
            transaction.executeWithoutResult(s -> {
                for (ArchivageService.Preparation p : preparations) {
                    consigner(job, archivage.appliquer(p, acteur, job.id()), p);
                }
                prolongerBail(job.id());
            });
        } catch (RuntimeException e) {
            log.warn("Archivage : tranche du job {} en échec, reprise document par document", job.id(), e);
            for (ArchivageService.Preparation p : preparations) {
                try {
                    transaction.executeWithoutResult(s -> consigner(job, archivage.appliquer(p, acteur, job.id()), p));
                } catch (RuntimeException ex) {
                    archivage.abandonner(p);
                    transaction.executeWithoutResult(s -> consigner(job, new ArchivageService.Resultat(p.documentId(),
                            ArchivageService.Issue.ECHEC, null, message(ex)), p));
                }
            }
            transaction.executeWithoutResult(s -> prolongerBail(job.id()));
        }
    }

    private void consigner(Job job, ArchivageService.Resultat r, ArchivageService.Preparation p) {
        if (r.issue() != ArchivageService.Issue.ARCHIVE && r.issue() != ArchivageService.Issue.ANOMALIE) {
            archivage.abandonner(p);
        }
        jdbc.update("UPDATE job_archivage_element SET resultat = ?, motif = ?, traite_le = now() "
                + "WHERE job_archivage_id = ? AND document_id = ?", r.issue().name(), r.motif(), job.id(), r.documentId());
        // Un document archivé avec anomalie compte parmi les archivés ET parmi les anomalies.
        String compteur = switch (r.issue()) {
            case ARCHIVE -> ", archives = archives + 1";
            case ANOMALIE -> ", archives = archives + 1, anomalies = anomalies + 1";
            case ECHEC -> ", echecs = echecs + 1";
            default -> "";
        };
        String sql = "UPDATE job_archivage SET traites = traites + 1" + compteur + " WHERE id = ?";
        jdbc.update(sql, job.id());
    }

    private void prolongerBail(UUID jobId) {
        jdbc.update("UPDATE job_archivage SET verrouille_jusqu_a = ? WHERE id = ?", bail(), jobId);
    }

    private Timestamp bail() {
        return Timestamp.from(Instant.now().plus(proprietes.getTravailleur().getBail()));
    }

    private static Instant instant(Timestamp t) {
        return t != null ? t.toInstant() : null;
    }

    private static String message(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
