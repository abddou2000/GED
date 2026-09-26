package com.ipt.ged.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

/**
 * Base PostgreSQL réelle pour les tests d'intégration des lots E5/E6 (file
 * SKIP LOCKED, tsvector french/arabic) : H2 ne sait rien de tout cela.
 *
 * <p>Base de dev3 ({@code ged_dev3_test}, brief d'équipe règle 4), URL
 * surchargeable par {@code GED_TEST_PG_URL}. Chaque classe de test travaille
 * dans un <b>schéma jetable</b> créé à la volée, où les changesets
 * « a-integrer » sont appliqués par Liquibase comme ils le seront en
 * production ; le schéma est supprimé à la fin.
 */
public final class BasePostgres implements AutoCloseable {

    private static final String URL = System.getenv().getOrDefault("GED_TEST_PG_URL",
            "jdbc:postgresql://localhost:5432/ged_dev3_test");
    private static final String UTILISATEUR = System.getenv().getOrDefault("GED_TEST_PG_UTILISATEUR", "postgres");
    private static final String MDP = System.getenv().getOrDefault("GED_TEST_PG_MDP", "");

    private final String schema;
    private final HikariDataSource source;

    private BasePostgres(String schema, HikariDataSource source) {
        this.schema = schema;
        this.source = source;
    }

    /** Crée un schéma jetable et y applique les changesets. */
    public static BasePostgres ouvrir() throws Exception {
        String schema = "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(URL, UTILISATEUR, MDP);
             Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public");
        cfg.setUsername(UTILISATEUR);
        cfg.setPassword(MDP);
        cfg.setMaximumPoolSize(12);
        cfg.setPoolName("pg-" + schema);
        HikariDataSource ds = new HikariDataSource(cfg);
        try (Connection c = ds.getConnection()) {
            Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
            db.setDefaultSchemaName(schema);
            try (Liquibase lb = new Liquibase("db/test-postgres/maitre.xml", new ClassLoaderResourceAccessor(), db)) {
                lb.update(new Contexts());
            }
        }
        return new BasePostgres(schema, ds);
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

    /** Insère un document et sa version simulés (clés étrangères du socle). */
    public void document(UUID documentId, UUID versionId) {
        JdbcTemplate j = jdbc();
        j.update("INSERT INTO document (id, nom) VALUES (?, 'test') ON CONFLICT DO NOTHING", documentId);
        j.update("INSERT INTO version_document (id, document_id) VALUES (?, ?)", versionId, documentId);
    }

    /** Clé de fichier simulée (clé étrangère ocr_job.fichier_id). */
    public void cleFichier(UUID fichierId) {
        jdbc().update("INSERT INTO cle_fichier (id, dek_enveloppee, kek_id) VALUES (?, ?, 'kek-00001')",
                fichierId, new byte[60]);
    }

    @Override
    public void close() throws Exception {
        source.close();
        try (Connection c = DriverManager.getConnection(URL, UTILISATEUR, MDP);
             Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
