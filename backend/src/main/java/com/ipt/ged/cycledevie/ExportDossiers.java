package com.ipt.ged.cycledevie;

import com.ipt.ged.document.evenement.Acteur;
import com.ipt.ged.document.evenement.DocumentExporte;
import com.ipt.ged.fichier.StockageChiffre;
import com.ipt.ged.recherche.FragmentSql;
import com.ipt.ged.recherche.PredicatDroits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Export d'un dossier en archive ZIP avec manifeste (§12.10).
 *
 * <ul>
 *   <li><b>En flux</b> ({@link ZipOutputStream}) : aucun fichier temporaire de la
 *       taille de l'archive ; chaque fichier est déchiffré au fil de l'eau.</li>
 *   <li><b>Contenu</b> : l'arborescence du dossier et de ses sous-dossiers, les
 *       documents à leur version courante, et {@code manifeste.csv} (UTF-8) :
 *       identifiant, chemins (rattachements compris), nom, objet, type, dates
 *       du document et du dépôt, déposant, niveau de confidentialité, statut,
 *       version, empreinte SHA-256. Un document rattaché plusieurs fois figure
 *       une seule fois.</li>
 *   <li><b>Filtrage</b> : le même prédicat de droits que la recherche
 *       ({@link PredicatDroits}) ; un document non autorisé est omis
 *       silencieusement ; un événement {@code DOCUMENT_EXPORTE} par document
 *       exporté.</li>
 *   <li>Au-delà de 500 documents ou de 2 Go : <b>traitement de fond</b>, dont
 *       l'archive (chiffrée comme tout fichier) se télécharge depuis la liste
 *       des exports de l'utilisateur, jusqu'à son expiration.</li>
 * </ul>
 */
@Service
public class ExportDossiers {

    private static final Logger log = LoggerFactory.getLogger(ExportDossiers.class);
    private static final String INSTANCE = ManagementFactory.getRuntimeMXBean().getName();
    static final String MANIFESTE = "manifeste.csv";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate nomme;
    private final Dossiers dossiers;
    private final PredicatDroits droits;
    private final StockageChiffre stockage;
    private final ApplicationEventPublisher evenements;
    private final ProprietesCycleDeVie proprietes;
    private final TransactionTemplate transaction;

    public ExportDossiers(JdbcTemplate jdbc, Dossiers dossiers, PredicatDroits droits, StockageChiffre stockage,
                          ApplicationEventPublisher evenements, ProprietesCycleDeVie proprietes,
                          PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.nomme = new NamedParameterJdbcTemplate(jdbc);
        this.dossiers = dossiers;
        this.droits = droits;
        this.stockage = stockage;
        this.evenements = evenements;
        this.proprietes = proprietes;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** Un document exporté : son entrée dans l'archive et sa ligne de manifeste. */
    public record Ligne(UUID documentId, UUID versionId, UUID cleFichierId, List<String> chemins, String nom,
                        String extension, String objet, String type, String dateDocument, Instant deposeLe,
                        String deposant, String confidentialite, String statut, int version, String empreinte,
                        long tailleOctets, String entree) {
    }

    /** Documents autorisés du dossier, prêts à écrire. */
    public record Selection(UUID dossierId, String dossierNom, List<Ligne> lignes, long tailleOctets) {
    }

    /** Export en traitement de fond, tel que listé à l'utilisateur. */
    public record Export(UUID id, UUID dossierId, String dossierNom, String etat, int nbDocuments,
                         long tailleEstimee, Long tailleOctets, String motif, Instant creeLe, Instant termineLe,
                         Instant expireLe) {
    }

    private static final RowMapper<Export> EXPORT = (rs, i) -> new Export(rs.getObject("id", UUID.class),
            rs.getObject("dossier_id", UUID.class), rs.getString("dossier_nom"), rs.getString("etat"),
            rs.getInt("nb_documents"), rs.getLong("taille_estimee"),
            (Long) rs.getObject("taille_octets"), rs.getString("motif"), instant(rs.getTimestamp("cree_le")),
            instant(rs.getTimestamp("termine_le")), instant(rs.getTimestamp("expire_le")));

    /* ======================= Sélection ======================= */

    /**
     * Documents du dossier (et sous-dossiers) que l'utilisateur peut voir, à
     * leur version courante. 404 si le dossier n'existe pas.
     */
    public Selection selectionner(UUID dossierId, Authentication utilisateur) {
        Dossiers.Dossier dossier = dossiers.trouver(dossierId)
                .orElseThrow(() -> ErreurCycleDeVie.introuvable("Dossier " + dossierId));
        List<Dossiers.DocumentRange> ranges = dossiers.documents(dossierId);
        Map<UUID, List<String>> chemins = new LinkedHashMap<>();
        ranges.forEach(r -> chemins.put(r.documentId(), r.chemins()));
        List<Ligne> lignes = details(chemins, droits.predicat("d.id", utilisateur));
        return construire(dossier, lignes);
    }

    /** Relit une sélection figée (traitement de fond) : documents encore présents, hors corbeille. */
    private Selection reprendre(UUID dossierId, String dossierNom, List<UUID> documents) {
        Map<UUID, List<String>> chemins = new LinkedHashMap<>();
        Map<UUID, List<String>> connus = new HashMap<>();
        dossiers.documents(dossierId).forEach(r -> connus.put(r.documentId(), r.chemins()));
        for (UUID id : documents) chemins.put(id, connus.getOrDefault(id, List.of("")));
        List<Ligne> lignes = details(chemins, FragmentSql.VRAI);
        return construire(new Dossiers.Dossier(dossierId, dossierNom, false), lignes);
    }

    private List<Ligne> details(Map<UUID, List<String>> chemins, FragmentSql predicat) {
        if (chemins.isEmpty()) return List.of();
        MapSqlParameterSource p = new MapSqlParameterSource("ids", chemins.keySet().toArray(new UUID[0]));
        predicat.parametres().forEach(p::addValue);
        List<Ligne> brutes = nomme.query("""
                SELECT d.id, d.name, d.extension, t.type_de_document, d.created_at, d.statut_conservation,
                       d.metadonnees ->> 'objet' AS objet, d.metadonnees ->> 'date_document' AS date_document,
                       d.metadonnees ->> 'confidentialite' AS confidentialite,
                       trim(coalesce(e.first_name, '') || ' ' || coalesce(e.last_name, '')) AS deposant,
                       v.id AS version_id, v.cle_fichier_id, v.empreinte, v.taille_octets,
                       (SELECT count(*) FROM version_document v2 WHERE v2.document_id = d.id
                          AND (v2.created_at, v2.id) <= (v.created_at, v.id)) AS numero
                  FROM document d
                  JOIN type_document t ON t.id = d.type_document_id
                  LEFT JOIN employe e ON e.id = d.created_by_employe_id
                  JOIN LATERAL (SELECT * FROM version_document x WHERE x.document_id = d.id
                                 ORDER BY x.is_default DESC, x.created_at DESC NULLS LAST, x.id DESC LIMIT 1) v ON true
                 WHERE d.id = ANY(:ids) AND NOT d.deleted AND v.cle_fichier_id IS NOT NULL AND (""" + predicat.sql() + ")",
                p, (rs, i) -> new Ligne(rs.getObject("id", UUID.class), rs.getObject("version_id", UUID.class),
                        rs.getObject("cle_fichier_id", UUID.class), List.of(), rs.getString("name"),
                        rs.getString("extension"), rs.getString("objet"), rs.getString("type_de_document"),
                        rs.getString("date_document"), instant(rs.getTimestamp("created_at")),
                        rs.getString("deposant"), rs.getString("confidentialite"), rs.getString("statut_conservation"),
                        rs.getInt("numero"), rs.getString("empreinte"), rs.getLong("taille_octets"), null));
        Map<UUID, Ligne> parId = new HashMap<>();
        brutes.forEach(l -> parId.put(l.documentId(), l));
        List<Ligne> lignes = new ArrayList<>();
        for (Map.Entry<UUID, List<String>> e : chemins.entrySet()) {
            Ligne l = parId.get(e.getKey());
            if (l != null) {
                lignes.add(new Ligne(l.documentId(), l.versionId(), l.cleFichierId(), e.getValue(), l.nom(),
                        l.extension(), l.objet(), l.type(), l.dateDocument(), l.deposeLe(), l.deposant(),
                        l.confidentialite(), l.statut(), l.version(), l.empreinte(), l.tailleOctets(), null));
            }
        }
        return lignes;
    }

    /** Noms d'entrée uniques dans l'archive : « racine/chemin/nom.ext », « nom (2).ext » en cas de doublon. */
    private static Selection construire(Dossiers.Dossier dossier, List<Ligne> lignes) {
        String racine = nettoyer(dossier.nom(), "dossier");
        Set<String> prises = new HashSet<>();
        List<Ligne> nommees = new ArrayList<>();
        long total = 0;
        for (Ligne l : lignes) {
            String chemin = l.chemins().isEmpty() ? "" : l.chemins().get(0);
            StringBuilder dossierZip = new StringBuilder(racine);
            for (String segment : chemin.split("/")) {
                if (!segment.isBlank()) dossierZip.append('/').append(nettoyer(segment, "dossier"));
            }
            String base = nettoyer(l.nom(), "document");
            String ext = l.extension() != null && !l.extension().isBlank() ? "." + nettoyer(l.extension(), "") : "";
            String entree = dossierZip + "/" + base + ext;
            for (int n = 2; !prises.add(entree.toLowerCase()); n++) {
                entree = dossierZip + "/" + base + " (" + n + ")" + ext;
            }
            total += l.tailleOctets();
            nommees.add(new Ligne(l.documentId(), l.versionId(), l.cleFichierId(), l.chemins(), l.nom(),
                    l.extension(), l.objet(), l.type(), l.dateDocument(), l.deposeLe(), l.deposant(),
                    l.confidentialite(), l.statut(), l.version(), l.empreinte(), l.tailleOctets(), entree));
        }
        return new Selection(dossier.id(), dossier.nom(), nommees, total);
    }

    /** Au-delà des seuils (500 documents ou 2 Go), l'export part en traitement de fond. */
    public boolean enTraitementDeFond(Selection s) {
        ProprietesCycleDeVie.Export e = proprietes.getExport();
        return s.lignes().size() > e.getSeuilDocuments() || s.tailleOctets() > e.getSeuilOctets();
    }

    /* ======================= Écriture ======================= */

    /** Publie un événement {@code DOCUMENT_EXPORTE} par document de la sélection. */
    public void journaliser(Selection s, Acteur acteur, UUID exportId) {
        transaction.executeWithoutResult(t -> s.lignes().forEach(l -> evenements.publishEvent(
                new DocumentExporte(l.documentId(), l.versionId(), acteur, Instant.now(), exportId, l.empreinte()))));
    }

    /** Écrit l'archive en flux : manifeste d'abord, puis chaque document déchiffré au fil de l'eau. */
    public void ecrire(Selection s, OutputStream sortie) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(sortie, StandardCharsets.UTF_8);
        Set<String> repertoires = new HashSet<>();
        zip.putNextEntry(new ZipEntry(nettoyer(s.dossierNom(), "dossier") + "/" + MANIFESTE));
        zip.write(manifeste(s).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        for (Ligne l : s.lignes()) {
            String rep = l.entree().substring(0, l.entree().lastIndexOf('/') + 1);
            if (repertoires.add(rep)) {
                zip.putNextEntry(new ZipEntry(rep));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry(l.entree()));
            try (InputStream in = stockage.lire(l.cleFichierId())) {
                in.transferTo(zip);
            }
            zip.closeEntry();
        }
        zip.finish();
        zip.flush();
    }

    /**
     * Archive en flux à lire par l'appelant : un fil dédié l'écrit dans un tube.
     * Si le lecteur abandonne (client déconnecté), le fil s'arrête à l'écriture
     * suivante ; si l'écriture échoue, le lecteur voit la fin du flux (archive
     * tronquée, signalée au journal technique).
     */
    public InputStream fluxZip(Selection s) throws IOException {
        PipedInputStream entree = new PipedInputStream(1 << 16);
        PipedOutputStream sortie = new PipedOutputStream(entree);
        Thread fil = new Thread(() -> {
            try (sortie) {
                ecrire(s, sortie);
            } catch (IOException | RuntimeException e) {
                log.warn("Export du dossier {} interrompu : {}", s.dossierId(), e.getMessage());
            }
        }, "ged-export-zip");
        fil.setDaemon(true);
        fil.start();
        return entree;
    }

    /** Manifeste CSV (UTF-8 avec BOM, séparateur « ; », guillemets RFC 4180). */
    static String manifeste(Selection s) {
        StringBuilder csv = new StringBuilder("﻿");
        csv.append(String.join(";", "identifiant", "fichier", "chemins", "nom", "objet", "type", "date_document",
                "date_depot", "deposant", "confidentialite", "statut", "version", "empreinte_sha256")).append("\r\n");
        for (Ligne l : s.lignes()) {
            String chemins = String.join(" | ", l.chemins().stream()
                    .map(c -> c.isBlank() ? s.dossierNom() : s.dossierNom() + "/" + c).toList());
            csv.append(String.join(";", champ(l.documentId().toString()), champ(l.entree()), champ(chemins),
                    champ(l.nom()), champ(l.objet()), champ(l.type()), champ(l.dateDocument()),
                    champ(l.deposeLe() != null ? l.deposeLe().toString() : null), champ(l.deposant()),
                    champ(l.confidentialite()), champ(l.statut()), champ(String.valueOf(l.version())),
                    champ(l.empreinte()))).append("\r\n");
        }
        return csv.toString();
    }

    private static String champ(String v) {
        if (v == null) return "";
        // Neutralise l'injection de formule dans un tableur.
        String s = !v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0 ? "'" + v : v;
        return s.contains(";") || s.contains("\"") || s.contains("\n") || s.contains("\r")
                ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    /** Segment de chemin sûr dans une archive : ni séparateur, ni caractère de contrôle, ni « .. ». */
    static String nettoyer(String nom, String defaut) {
        if (nom == null) return defaut;
        String s = nom.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        s = s.replaceAll("^\\.+", "_");
        if (s.length() > 150) s = s.substring(0, 150);
        return s.isBlank() ? defaut : s;
    }

    /* ======================= Traitement de fond ======================= */

    /** Crée un export de fond pour la sélection (figée), à l'usage de son seul demandeur. */
    public Export differer(Selection s, UUID demandeurId) {
        if (demandeurId == null) throw ErreurCycleDeVie.permission("Exporter");
        UUID id = UUID.randomUUID();
        transaction.executeWithoutResult(t -> {
            jdbc.update("INSERT INTO job_export (id, dossier_id, dossier_nom, demandeur_employe_id, etat, nb_documents, "
                            + "taille_estimee) VALUES (?, ?, ?, ?, 'EN_ATTENTE', ?, ?)", id, s.dossierId(), s.dossierNom(),
                    demandeurId, s.lignes().size(), s.tailleOctets());
            List<Object[]> lignes = new ArrayList<>();
            for (int i = 0; i < s.lignes().size(); i++) lignes.add(new Object[]{id, s.lignes().get(i).documentId(), i});
            jdbc.batchUpdate("INSERT INTO job_export_element (job_export_id, document_id, rang) VALUES (?, ?, ?)", lignes);
        });
        return export(id, demandeurId).orElseThrow();
    }

    /** Exports de l'utilisateur, du plus récent au plus ancien. */
    public List<Export> exports(UUID demandeurId) {
        if (demandeurId == null) return List.of();
        return jdbc.query("SELECT * FROM job_export WHERE demandeur_employe_id = ? ORDER BY cree_le DESC LIMIT 100",
                EXPORT, demandeurId);
    }

    /** Un export de l'utilisateur ; celui d'un autre est introuvable (jamais distingué, P5). */
    public Optional<Export> export(UUID id, UUID demandeurId) {
        return jdbc.query("SELECT * FROM job_export WHERE id = ? AND demandeur_employe_id = ?", EXPORT, id, demandeurId)
                .stream().findFirst();
    }

    /** Archive d'un export terminé de l'utilisateur, déchiffrée en flux. */
    public InputStream ouvrir(UUID id, UUID demandeurId) {
        Export e = export(id, demandeurId).orElseThrow(() -> ErreurCycleDeVie.introuvable("Export " + id));
        UUID fichier = jdbc.queryForObject("SELECT cle_fichier_id FROM job_export WHERE id = ?", UUID.class, id);
        if (!"TERMINE".equals(e.etat()) || fichier == null) {
            throw ErreurCycleDeVie.conflit(ErreurCycleDeVie.EXPORT_INDISPONIBLE,
                    "Export non disponible (état " + e.etat() + ").");
        }
        return stockage.lire(fichier);
    }

    /** Réserve un export en attente (ou abandonné) et produit son archive chiffrée. */
    public boolean traiterUnJob() {
        List<Map<String, Object>> reserve = transaction.execute(t -> jdbc.queryForList("""
                UPDATE job_export SET etat = 'EN_COURS', verrouille_par = ?, verrouille_jusqu_a = ?
                 WHERE id = (SELECT id FROM job_export
                              WHERE etat IN ('EN_ATTENTE', 'EN_COURS')
                                AND (verrouille_jusqu_a IS NULL OR verrouille_jusqu_a < now())
                              ORDER BY cree_le FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING id, dossier_id, dossier_nom, demandeur_employe_id
                """, INSTANCE, Timestamp.from(Instant.now().plus(proprietes.getTravailleur().getBail()))));
        if (reserve == null || reserve.isEmpty()) return false;
        Map<String, Object> j = reserve.get(0);
        UUID id = (UUID) j.get("id");
        UUID demandeur = (UUID) j.get("demandeur_employe_id");
        try {
            List<UUID> documents = jdbc.queryForList(
                    "SELECT document_id FROM job_export_element WHERE job_export_id = ? ORDER BY rang", UUID.class, id);
            Selection s = reprendre((UUID) j.get("dossier_id"), (String) j.get("dossier_nom"), documents);
            StockageChiffre.ResultatStockage r = produire(s);
            transaction.executeWithoutResult(t -> {
                jdbc.update("UPDATE job_export SET etat = 'TERMINE', cle_fichier_id = ?, taille_octets = ?, "
                                + "nb_documents = ?, termine_le = now(), expire_le = ?, verrouille_par = NULL, "
                                + "verrouille_jusqu_a = NULL WHERE id = ?", r.id(), r.tailleOctets(), s.lignes().size(),
                        Timestamp.from(Instant.now().plus(proprietes.getExport().getRetention())), id);
                s.lignes().forEach(l -> evenements.publishEvent(new DocumentExporte(l.documentId(), l.versionId(),
                        new Acteur(demandeur, null), Instant.now(), id, l.empreinte())));
            });
            log.info("Export {} produit : {} documents, {} octets", id, s.lignes().size(), r.tailleOctets());
        } catch (RuntimeException | IOException e) {
            log.warn("Export {} en échec", id, e);
            jdbc.update("UPDATE job_export SET etat = 'ECHEC', motif = ?, termine_le = now(), verrouille_par = NULL, "
                    + "verrouille_jusqu_a = NULL WHERE id = ?", e.getMessage() != null ? e.getMessage() : e.toString(), id);
        }
        return true;
    }

    /** Archive écrite en flux directement dans le stockage chiffré (aucune copie en clair sur disque). */
    private StockageChiffre.ResultatStockage produire(Selection s) throws IOException {
        PipedInputStream entree = new PipedInputStream(1 << 16);
        PipedOutputStream sortie = new PipedOutputStream(entree);
        ExecutorService fil = Executors.newSingleThreadExecutor(r -> new Thread(r, "ged-export-zip"));
        try {
            Future<?> ecriture = fil.submit(() -> {
                try (sortie) {
                    ecrire(s, sortie);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return null;
            });
            StockageChiffre.ResultatStockage r;
            try (entree) {
                r = stockage.ecrire(entree, proprietes.getExport().getTailleMaxOctets());
            }
            try {
                ecriture.get();
            } catch (Exception e) {
                stockage.detruire(r.id());
                throw new IOException("écriture de l'archive interrompue", e);
            }
            return r;
        } finally {
            fil.shutdownNow();
        }
    }

    /** Détruit les exports expirés (clé puis fichier). */
    public int expirer() {
        List<Map<String, Object>> expires = jdbc.queryForList("SELECT id, cle_fichier_id FROM job_export "
                + "WHERE etat = 'TERMINE' AND expire_le < now()");
        for (Map<String, Object> e : expires) {
            UUID fichier = (UUID) e.get("cle_fichier_id");
            jdbc.update("UPDATE job_export SET etat = 'EXPIRE', cle_fichier_id = NULL WHERE id = ?", e.get("id"));
            if (fichier != null) stockage.detruire(fichier);
        }
        return expires.size();
    }

    private static Instant instant(Timestamp t) {
        return t != null ? t.toInstant() : null;
    }
}
