package com.ipt.ged.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

/**
 * Schéma PostgreSQL <b>jetable</b> pour les tests d'intégration des lots E5/E6
 * (file SKIP LOCKED, tsvector french/arabic, clés étrangères réelles) : le
 * changelog maître complet y est appliqué par Liquibase, avec le compte
 * propriétaire {@code ged_owner}, comme au déploiement ; le schéma est
 * supprimé à la fermeture. La base de test partagée par les tests Spring
 * (schéma {@code ged}) n'est pas touchée.
 *
 * <p>Même connexion que le profil {@code test} : {@code DB_HOST}, {@code DB_PORT},
 * {@code DB_NAME_TEST} (sinon {@code <DB_NAME>_test}), {@code DB_OWNER_USER},
 * {@code DB_OWNER_PASSWORD}. Prérequis : {@code preparer-base.sql -v tests=oui}.
 */
public final class BasePostgres implements AutoCloseable {

    private static final String CHANGELOG = "db/changelog/db.changelog-master.xml";

    private final String url;
    private final String utilisateur;
    private final String mdp;
    private final String schema;
    private final HikariDataSource source;
    private UUID workspace;
    private UUID typeDocument;

    private BasePostgres(String url, String utilisateur, String mdp, String schema, HikariDataSource source) {
        this.url = url;
        this.utilisateur = utilisateur;
        this.mdp = mdp;
        this.schema = schema;
        this.source = source;
    }

    private static String env(String nom, String defaut) {
        Map<String, String> e = System.getenv();
        String v = e.get(nom);
        return v == null || v.isBlank() ? defaut : v;
    }

    /** Crée le schéma jetable et y applique tout le changelog maître. */
    public static BasePostgres ouvrir() throws Exception {
        String base = env("DB_NAME_TEST", env("DB_NAME", "ged_dev1") + "_test");
        String url = "jdbc:postgresql://" + env("DB_HOST", "localhost") + ":" + env("DB_PORT", "5432") + "/" + base;
        String utilisateur = env("DB_OWNER_USER", "ged_owner");
        String mdp = env("DB_OWNER_PASSWORD", "");
        String schema = "ged_verif_e6_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(url, utilisateur, mdp);
             Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url + "?currentSchema=" + schema);
        cfg.setUsername(utilisateur);
        cfg.setPassword(mdp);
        cfg.setMaximumPoolSize(12);
        cfg.setPoolName("pg-" + schema);
        HikariDataSource ds = new HikariDataSource(cfg);
        try (Connection c = ds.getConnection()) {
            Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
            db.setDefaultSchemaName(schema);
            db.setLiquibaseSchemaName(schema);
            try (Liquibase lb = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), db)) {
                lb.update(new Contexts(), new LabelExpression());
            }
        }
        return new BasePostgres(url, utilisateur, mdp, schema, ds);
    }

    /** Applique en plus un changelog (ex. le « contract » de la version suivante) sur le schéma jetable. */
    public void appliquer(String changelog) throws Exception {
        try (Connection c = source.getConnection()) {
            Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
            db.setDefaultSchemaName(schema);
            db.setLiquibaseSchemaName(schema);
            try (Liquibase lb = new Liquibase(changelog, new ClassLoaderResourceAccessor(), db)) {
                lb.update(new Contexts(), new LabelExpression());
            }
        }
    }

    /** Retour arrière des {@code n} derniers changesets d'un changelog. */
    public void annuler(String changelog, int n) throws Exception {
        try (Connection c = source.getConnection()) {
            Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
            db.setDefaultSchemaName(schema);
            db.setLiquibaseSchemaName(schema);
            try (Liquibase lb = new Liquibase(changelog, new ClassLoaderResourceAccessor(), db)) {
                lb.rollback(n, (String) null);
            }
        }
    }

    public HikariDataSource source() {
        return source;
    }

    public JdbcTemplate jdbc() {
        return new JdbcTemplate(source);
    }

    public String schema() {
        return schema;
    }

    /**
     * Insère un document et sa version (si {@code versionId} n'est pas nul),
     * avec l'espace et le type requis par les clés étrangères du modèle (lot E1).
     */
    public synchronized void document(UUID documentId, UUID versionId) {
        JdbcTemplate j = jdbc();
        if (workspace == null) {
            UUID employe = UUID.randomUUID(), workflow = UUID.randomUUID();
            workspace = UUID.randomUUID();
            typeDocument = UUID.randomUUID();
            j.update("INSERT INTO employe (id, first_name, last_name) VALUES (?, 'Test', 'E6')", employe);
            j.update("INSERT INTO workflow_ged (id, name) VALUES (?, 'circuit de test')", workflow);
            j.update("INSERT INTO workspace (id, name, code, status, employe_id, workflow_ged_id) "
                    + "VALUES (?, 'espace de test', ?, 'ACTIF', ?, ?)", workspace, "ESP-" + workspace, employe, workflow);
            j.update("INSERT INTO type_document (id, code, type_de_document, description, workspace_id) "
                    + "VALUES (?, ?, 'Type de test', 'test', ?)", typeDocument, "TD-" + typeDocument, workspace);
        }
        j.update("INSERT INTO document (id, name, workspace_id, type_document_id) VALUES (?, 'test', ?, ?) "
                + "ON CONFLICT (id) DO NOTHING", documentId, workspace, typeDocument);
        if (versionId != null) {
            j.update("INSERT INTO version_document (id, document_id, file_name, file_path) VALUES (?, ?, 'f.pdf', 'x/f.pdf')",
                    versionId, documentId);
        }
    }

    /** Version supplémentaire d'un document existant, avec son chemin dans l'ancien stockage en clair. */
    public void version(UUID documentId, UUID versionId, String cheminEnClair, boolean principale) {
        document(documentId, null);
        jdbc().update("INSERT INTO version_document (id, document_id, file_name, file_path, is_default) "
                + "VALUES (?, ?, 'f.pdf', ?, ?)", versionId, documentId, cheminEnClair, principale);
    }

    /** Clé de fichier (clé étrangère ocr_job.cle_fichier_id). */
    public void cleFichier(UUID fichierId) {
        jdbc().update("INSERT INTO cle_fichier (id, dek_enveloppee, kek_identifiant) VALUES (?, ?, 'kek-00001')",
                fichierId, new byte[60]);
    }

    @Override
    public void close() throws Exception {
        source.close();
        try (Connection c = DriverManager.getConnection(url, utilisateur, mdp);
             Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
