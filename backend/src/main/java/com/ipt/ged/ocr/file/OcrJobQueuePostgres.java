package com.ipt.ged.ocr.file;

import com.ipt.ged.common.UuidV7;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link OcrJobQueue} sur la table PostgreSQL {@code ocr_job} (changeset
 * {@code 202609271005_ocr_job.xml}).
 *
 * <p><b>Réservation.</b> Une seule instruction, atomique :
 * {@code UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED LIMIT n) RETURNING …}.
 * Les lignes verrouillées par un autre worker sont sautées au lieu d'être
 * attendues : plusieurs workers (fils ou instances) se partagent la file sans
 * doublon et sans se bloquer.
 *
 * <p><b>Bail.</b> La réservation ne garde pas de transaction ouverte pendant
 * l'OCR (un document de 800 pages peut prendre des heures) : elle pose un bail
 * ({@code verrouille_jusqu_a}) que le worker prolonge à chaque page. Un worker
 * arrêté brutalement laisse un bail qui expire ; le job redevient alors
 * éligible, et l'exécution interrompue compte comme une tentative.
 *
 * <p><b>Ordre.</b> Le flux courant avant la reprise ({@link PrioriteOcr},
 * colonne {@code priorite}, R31), puis le plus ancien dépôt d'abord : c'est lui
 * qui menace l'objectif de délai de disponibilité (D6, 24 h).
 *
 * <p>Toutes les dates viennent de l'horloge de la base : les workers de
 * plusieurs serveurs comparent ainsi la même heure.
 */
public class OcrJobQueuePostgres implements OcrJobQueue {

    private static final String COLONNES = "id, document_id, version_id, cle_fichier_id, type_mime, langue, statut, "
            + "tentatives, prochaine_tentative_le, verrouille_par, verrouille_jusqu_a, motif_echec, nb_pages, "
            + "depose_le, cree_le, termine_le";
    private static final String ACTIFS = "('EN_ATTENTE_OCR', 'EN_COURS_OCR')";
    private static final int MOTIF_MAX = 2000;

    private static final RowMapper<OcrJob> LIGNE = (rs, i) -> new OcrJob(
            rs.getObject("id", UUID.class),
            rs.getObject("document_id", UUID.class),
            rs.getObject("version_id", UUID.class),
            rs.getObject("cle_fichier_id", UUID.class),
            rs.getString("type_mime"),
            rs.getString("langue"),
            StatutOcr.valueOf(rs.getString("statut")),
            rs.getInt("tentatives"),
            instant(rs, "prochaine_tentative_le"),
            rs.getString("verrouille_par"),
            instant(rs, "verrouille_jusqu_a"),
            rs.getString("motif_echec"),
            (Integer) rs.getObject("nb_pages"),
            instant(rs, "depose_le"),
            instant(rs, "cree_le"),
            instant(rs, "termine_le"));

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate nomme;
    private final PolitiqueReprise politique;

    public OcrJobQueuePostgres(JdbcTemplate jdbc, PolitiqueReprise politique) {
        this.jdbc = jdbc;
        this.nomme = new NamedParameterJdbcTemplate(jdbc);
        this.politique = politique;
    }

    @Override
    public UUID enfiler(NouveauJob job) {
        UUID id = UuidV7.suivant();
        int crees = jdbc.update("INSERT INTO ocr_job (id, document_id, version_id, cle_fichier_id, type_mime, langue, "
                        + "statut, depose_le, priorite) VALUES (?, ?, ?, ?, ?, ?, 'EN_ATTENTE_OCR', ?, ?) "
                        + "ON CONFLICT (version_id) WHERE statut IN " + ACTIFS + " DO NOTHING",
                id, job.documentId(), job.versionId(), job.fichierId(), job.typeMime(), job.langue(),
                Timestamp.from(job.deposeLe() != null ? job.deposeLe() : Instant.now()), job.priorite().code());
        if (crees == 1) return id;
        return jdbc.queryForObject("SELECT id FROM ocr_job WHERE version_id = ? AND statut IN " + ACTIFS,
                UUID.class, job.versionId());
    }

    @Override
    public List<OcrJob> reserver(String worker, int nombre, Duration bail) {
        // Un bail expiré alors que les tentatives sont épuisées : le worker
        // est mort sur ce job autant de fois qu'on l'autorise — on clôt.
        jdbc.update("UPDATE ocr_job SET statut = 'OCR_ECHEC', termine_le = now(), modifie_le = now(), "
                        + "verrouille_par = NULL, verrouille_jusqu_a = NULL, "
                        + "motif_echec = 'DELAI_DEPASSE : traitement interrompu (bail expiré) à chaque tentative' "
                        + "WHERE statut = 'EN_COURS_OCR' AND verrouille_jusqu_a < now() AND tentatives >= ?",
                politique.executionsMax());
        return jdbc.query("UPDATE ocr_job j SET statut = 'EN_COURS_OCR', verrouille_par = ?, "
                        + "verrouille_jusqu_a = now() + (? * interval '1 millisecond'), "
                        + "tentatives = j.tentatives + 1, demarre_le = now(), modifie_le = now() "
                        + "WHERE j.id IN (SELECT id FROM ocr_job "
                        + "  WHERE (statut = 'EN_ATTENTE_OCR' AND prochaine_tentative_le <= now()) "
                        + "     OR (statut = 'EN_COURS_OCR' AND verrouille_jusqu_a < now()) "
                        + "  ORDER BY priorite, depose_le, id "
                        + "  LIMIT ? FOR UPDATE SKIP LOCKED) "
                        + "RETURNING " + prefixer(COLONNES),
                LIGNE, worker, bail.toMillis(), nombre);
    }

    @Override
    public boolean prolonger(UUID jobId, String worker, Duration bail) {
        return jdbc.update("UPDATE ocr_job SET verrouille_jusqu_a = now() + (? * interval '1 millisecond'), "
                        + "modifie_le = now() WHERE id = ? AND verrouille_par = ? AND statut = 'EN_COURS_OCR'",
                bail.toMillis(), jobId, worker) == 1;
    }

    @Override
    public boolean terminer(UUID jobId, String worker, int nbPages) {
        return jdbc.update("UPDATE ocr_job SET statut = 'OCR_TERMINE', termine_le = now(), modifie_le = now(), "
                        + "nb_pages = ?, motif_echec = NULL, verrouille_par = NULL, verrouille_jusqu_a = NULL "
                        + "WHERE id = ? AND verrouille_par = ? AND statut = 'EN_COURS_OCR'",
                nbPages, jobId, worker) == 1;
    }

    @Override
    public StatutOcr echouer(UUID jobId, String worker, String motif, boolean definitif) {
        OcrJob job = trouver(jobId).orElseThrow(() -> new IllegalArgumentException("Job OCR introuvable : " + jobId));
        String libelle = motif == null ? "" : motif.length() > MOTIF_MAX ? motif.substring(0, MOTIF_MAX) : motif;
        Optional<Duration> reprise = definitif ? Optional.empty() : politique.apres(job.tentatives());
        int n;
        StatutOcr statut;
        if (reprise.isPresent()) {
            statut = StatutOcr.EN_ATTENTE_OCR;
            n = jdbc.update("UPDATE ocr_job SET statut = 'EN_ATTENTE_OCR', motif_echec = ?, modifie_le = now(), "
                            + "prochaine_tentative_le = now() + (? * interval '1 millisecond'), "
                            + "verrouille_par = NULL, verrouille_jusqu_a = NULL "
                            + "WHERE id = ? AND verrouille_par = ? AND statut = 'EN_COURS_OCR' AND tentatives = ?",
                    libelle, reprise.get().toMillis(), jobId, worker, job.tentatives());
        } else {
            statut = StatutOcr.OCR_ECHEC;
            n = jdbc.update("UPDATE ocr_job SET statut = 'OCR_ECHEC', motif_echec = ?, termine_le = now(), "
                            + "modifie_le = now(), verrouille_par = NULL, verrouille_jusqu_a = NULL "
                            + "WHERE id = ? AND verrouille_par = ? AND statut = 'EN_COURS_OCR' AND tentatives = ?",
                    libelle, jobId, worker, job.tentatives());
        }
        // Bail perdu entre-temps : un autre worker a repris le job, son
        // issue fait foi.
        return n == 1 ? statut : trouver(jobId).map(OcrJob::statut).orElse(statut);
    }

    @Override
    public boolean relancer(UUID jobId) {
        try {
            return jdbc.update("UPDATE ocr_job SET statut = 'EN_ATTENTE_OCR', tentatives = 0, "
                    + "prochaine_tentative_le = now(), termine_le = NULL, modifie_le = now() "
                    + "WHERE id = ? AND statut = 'OCR_ECHEC'", jobId) == 1;
        } catch (DuplicateKeyException e) {
            return false; // la version a déjà un job actif plus récent
        }
    }

    @Override
    public Optional<OcrJob> trouver(UUID jobId) {
        return jdbc.query("SELECT " + COLONNES + " FROM ocr_job WHERE id = ?", LIGNE, jobId).stream().findFirst();
    }

    @Override
    public Map<UUID, StatutOcr> statutsParVersion(List<UUID> versionIds) {
        Map<UUID, StatutOcr> r = new HashMap<>();
        if (versionIds == null || versionIds.isEmpty()) return r;
        nomme.query("SELECT DISTINCT ON (version_id) version_id, statut FROM ocr_job "
                        + "WHERE version_id IN (:ids) ORDER BY version_id, cree_le DESC, id",
                new MapSqlParameterSource("ids", versionIds),
                rs -> { r.put(rs.getObject("version_id", UUID.class), StatutOcr.valueOf(rs.getString("statut"))); });
        return r;
    }

    @Override
    public List<OcrJob> lister(StatutOcr statut, int limite, int decalage) {
        return jdbc.query("SELECT " + COLONNES + " FROM ocr_job WHERE statut = ? "
                + "ORDER BY depose_le, id LIMIT ? OFFSET ?", LIGNE, statut.name(), limite, decalage);
    }

    @Override
    public long profondeur() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM ocr_job WHERE statut IN " + ACTIFS, Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public Optional<Instant> plusAncienDepotEnAttente() {
        OffsetDateTime t = jdbc.queryForObject("SELECT MIN(depose_le) FROM ocr_job WHERE statut IN " + ACTIFS
                        + " AND priorite = " + PrioriteOcr.FLUX_COURANT.code(), OffsetDateTime.class);
        return Optional.ofNullable(t).map(OffsetDateTime::toInstant);
    }

    @Override
    public Map<StatutOcr, Long> compterParStatut() {
        Map<StatutOcr, Long> r = new EnumMap<>(StatutOcr.class);
        for (StatutOcr s : StatutOcr.values()) r.put(s, 0L);
        jdbc.query("SELECT statut, COUNT(*) AS n FROM ocr_job GROUP BY statut",
                rs -> { r.put(StatutOcr.valueOf(rs.getString("statut")), rs.getLong("n")); });
        return r;
    }

    private static Instant instant(ResultSet rs, String colonne) throws SQLException {
        OffsetDateTime t = rs.getObject(colonne, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static String prefixer(String colonnes) {
        return "j." + colonnes.replace(", ", ", j.");
    }
}
