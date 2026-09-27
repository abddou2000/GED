package com.ipt.ged.socle;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le schéma est entièrement produit par Liquibase, et chaque changeset sait se
 * défaire (dossier technique §4.2.1, §4.2.2 ; critère de sortie de l'étape E1).
 *
 * <p>Le test travaille dans un schéma <b>jetable</b>, créé pour l'occasion avec
 * le compte propriétaire {@code ged_owner} : la base de test partagée par les
 * autres classes n'est pas touchée, et la démonstration part d'un schéma
 * réellement vide — pas d'un schéma « déjà migré » dont on ne saurait pas ce
 * qu'il contenait avant.
 *
 * <p>Prérequis : base de test préparée avec {@code preparer-base.sql -v tests=oui}
 * (droit CREATE de ged_owner sur la base).
 */
@SpringBootTest
@ActiveProfiles("test")
class SchemaLiquibaseTest {

    private static final String CHANGELOG = "db/changelog/db.changelog-master.xml";

    /** Tables du modèle à l'issue du lot E1 (jalon socle-e1). */
    private static final Set<String> TABLES_E1 = Set.of(
            "employe", "compte_utilisateur", "workflow_ged", "workflow_ged_etape", "workspace",
            "access_group", "access_group_workspace", "access_group_employe", "etiquette",
            "index_def", "plan_indexation", "plan_index", "type_document", "document",
            "version_document", "document_etiquette", "document_index_valeur",
            "workflow_ged_signature");

    /** Tables ajoutées par les lots E5 (stockage chiffré) et E6 (OCR, recherche plein texte). */
    private static final Set<String> TABLES_E5_E6 = Set.of("cle_fichier", "ocr_job", "document_texte");

    /** Lot E7, cycle de vie (dev3) : copies de conservation, jobs d'archivage et d'export. */
    private static final Set<String> TABLES_E7_CYCLE_DE_VIE = Set.of(
            "copie_conservation", "job_archivage", "job_archivage_element", "job_export", "job_export_element");

    /** Toutes les tables du changelog maître. */
    private static final Set<String> TABLES_ATTENDUES;
    static {
        Set<String> t = new TreeSet<>(TABLES_E1);
        t.addAll(TABLES_E5_E6);
        t.addAll(TABLES_E7_CYCLE_DE_VIE);
        TABLES_ATTENDUES = Set.copyOf(t);
    }

    /** Tables d'association, à clé composite : les seules sans colonne {@code id}. */
    private static final Set<String> ASSOCIATIONS = Set.of(
            "access_group_workspace", "access_group_employe", "plan_index", "document_etiquette",
            "job_archivage_element", "job_export_element");

    /** Tables à corbeille : portent l'auteur et la date de suppression. */
    private static final Set<String> A_CORBEILLE = Set.of(
            "workflow_ged", "workspace", "access_group", "etiquette", "index_def",
            "plan_indexation", "type_document", "document");

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.liquibase.user}")
    private String proprietaire;

    @Value("${spring.liquibase.password:}")
    private String motDePasseProprietaire;

    @Test
    @DisplayName("Base vierge : Liquibase crée tout le schéma, conventions respectées, puis tout se défait")
    void schemaCreeParLiquibasePuisRetourArriere() throws Exception {
        String schema = "ged_verif_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(url, proprietaire, motDePasseProprietaire)) {
            executer(c, "CREATE SCHEMA " + schema);
            try {
                Liquibase liquibase = liquibase(c, schema);
                assertTrue(tables(c, schema).isEmpty(), "le schéma jetable doit partir vide");

                // 1. Montée complète depuis un schéma vide.
                liquibase.update(new Contexts(), new LabelExpression());
                Set<String> tables = tables(c, schema);
                tables.removeAll(Set.of("databasechangelog", "databasechangeloglock"));
                assertEquals(new TreeSet<>(TABLES_ATTENDUES), tables);

                verifierClesUuid(c, schema);
                verifierConventionsDeNommage(c, schema);
                verifierSuppressionDouce(c, schema);
                verifierMetadonnees(c, schema);
                int changesets = compter(c, "SELECT count(*) FROM " + schema + ".databasechangelog");
                assertTrue(changesets >= 21, "tous les changesets doivent être enregistrés : " + changesets);

                // 2. Retour arrière de TOUS les changesets, dans l'ordre inverse :
                //    chacun porte une clause rollback qui doit s'exécuter sans erreur.
                liquibase.rollback(changesets, (String) null);
                Set<String> restantes = tables(c, schema);
                restantes.removeAll(Set.of("databasechangelog", "databasechangeloglock"));
                assertEquals(Set.of(), restantes, "après retour arrière complet, plus aucune table");
                assertEquals(0, compter(c, "SELECT count(*) FROM " + schema + ".databasechangelog"));

                // 3. Remontée : le retour arrière a laissé un schéma réutilisable.
                liquibase.update(new Contexts(), new LabelExpression());
                assertEquals(changesets, compter(c, "SELECT count(*) FROM " + schema + ".databasechangelog"));
            } finally {
                supprimerSchema(c, schema);
            }
        }
    }

    @Test
    @DisplayName("Retour arrière au jalon socle-e1 : l'étiquette posée par le changelog est utilisable")
    void retourArriereAuJalon() throws Exception {
        String schema = "ged_verif_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(url, proprietaire, motDePasseProprietaire)) {
            executer(c, "CREATE SCHEMA " + schema);
            try {
                Liquibase liquibase = liquibase(c, schema);
                liquibase.update(new Contexts(), new LabelExpression());
                // Changesets du lot E1 jusqu'au jalon, jalon exclu.
                String jusquAuJalon = " WHERE orderexecuted < (SELECT orderexecuted FROM " + schema
                        + ".databasechangelog WHERE tag = 'socle-e1')";
                int avant = compter(c, "SELECT count(*) FROM " + schema + ".databasechangelog" + jusquAuJalon);
                // Le retour arrière au jalon défait les lots postérieurs (E5, E6)
                // et le jalon lui-même (Liquibase inclut la ligne étiquetée), et
                // laisse intact tout le schéma du lot E1.
                liquibase.rollback("socle-e1", (String) null);
                assertEquals(avant, compter(c, "SELECT count(*) FROM " + schema + ".databasechangelog"));
                Set<String> tables = tables(c, schema);
                tables.removeAll(Set.of("databasechangelog", "databasechangeloglock"));
                assertEquals(new TreeSet<>(TABLES_E1), tables);
                // Rejouer la montée repose le jalon.
                liquibase.update(new Contexts(), new LabelExpression());
                assertEquals(1, compter(c, "SELECT count(*) FROM " + schema
                        + ".databasechangelog WHERE tag = 'socle-e1'"));
            } finally {
                supprimerSchema(c, schema);
            }
        }
    }

    /* ---------------------------------------------------------------- vérifications */

    /** Clé primaire {@code id} de type uuid sur toute table qui n'est pas une association. */
    private void verifierClesUuid(Connection c, String schema) throws SQLException {
        for (String table : TABLES_ATTENDUES) {
            if (ASSOCIATIONS.contains(table)) continue;
            String type = texte(c, "SELECT data_type FROM information_schema.columns"
                    + " WHERE table_schema = ? AND table_name = ? AND column_name = 'id'", schema, table);
            assertEquals("uuid", type, "clé primaire de " + table);
            String pk = texte(c, "SELECT constraint_name FROM information_schema.table_constraints"
                    + " WHERE table_schema = ? AND table_name = ? AND constraint_type = 'PRIMARY KEY'", schema, table);
            assertEquals("pk_" + table, pk);
        }
        // Toute colonne se terminant par _id (clé étrangère) est elle aussi un uuid.
        List<String> nonUuid = lignes(c, "SELECT table_name || '.' || column_name FROM information_schema.columns"
                + " WHERE table_schema = ? AND column_name LIKE '%\\_id' AND data_type <> 'uuid'"
                + " AND table_name NOT LIKE 'databasechangelog%'", schema);
        assertEquals(List.of(), nonUuid, "clés étrangères non UUID");
    }

    /** snake_case minuscule, préfixes pk_, uk_, fk_, ck_ et idx_ (§4.2.2). */
    private void verifierConventionsDeNommage(Connection c, String schema) throws SQLException {
        List<String> mauvaisNoms = lignes(c, """
                SELECT conname FROM pg_constraint k JOIN pg_namespace n ON n.oid = k.connamespace
                 WHERE n.nspname = ? AND conname !~ '^(pk|uk|fk|ck)_[a-z0-9_]+$'
                   AND contype <> 'n' AND conrelid::regclass::text NOT LIKE '%databasechangelog%'""", schema);
        assertEquals(List.of(), mauvaisNoms, "contraintes hors convention");

        List<String> mauvaisIndex = lignes(c, """
                SELECT indexname FROM pg_indexes
                 WHERE schemaname = ? AND tablename NOT LIKE 'databasechangelog%'
                   AND indexname !~ '^(pk|uk|idx)_[a-z0-9_]+$'""", schema);
        assertEquals(List.of(), mauvaisIndex, "index hors convention");

        List<String> mauvaisesColonnes = lignes(c, """
                SELECT table_name || '.' || column_name FROM information_schema.columns
                 WHERE table_schema = ? AND table_name NOT LIKE 'databasechangelog%'
                   AND (column_name !~ '^[a-z][a-z0-9_]*$' OR table_name !~ '^[a-z][a-z0-9_]*$')""", schema);
        assertEquals(List.of(), mauvaisesColonnes, "noms hors snake_case");

        // Une clé étrangère sur <table>_id doit viser <table> (le rôle éventuel
        // précède : created_by_employe_id, parent_id pour l'auto-référence).
        List<String> incoherentes = lignes(c, """
                SELECT tc.table_name || '.' || kcu.column_name || ' -> ' || ccu.table_name
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.key_column_usage kcu
                    ON kcu.constraint_name = tc.constraint_name AND kcu.table_schema = tc.table_schema
                  JOIN information_schema.constraint_column_usage ccu
                    ON ccu.constraint_name = tc.constraint_name AND ccu.table_schema = tc.table_schema
                 WHERE tc.table_schema = ? AND tc.constraint_type = 'FOREIGN KEY'
                   -- version_id : nom imposé par le dossier (document_texte, ocr_job, §4.4),
                   -- vise version_document.
                   -- archive_par : pendant de supprime_par (§12.6, auteur de l'archivage).
                   AND kcu.column_name NOT IN ('parent_id', 'supprime_par', 'archive_par', 'version_id')
                   AND kcu.column_name NOT LIKE '%' || ccu.table_name || '_id'""", schema);
        assertEquals(List.of(), incoherentes, "clés étrangères dont le nom ne désigne pas la table visée");
    }

    /** supprime_par (uuid) et supprime_le (timestamptz) partout où existe `deleted`. */
    private void verifierSuppressionDouce(Connection c, String schema) throws SQLException {
        List<String> avecDeleted = lignes(c, "SELECT table_name FROM information_schema.columns"
                + " WHERE table_schema = ? AND column_name = 'deleted' ORDER BY 1", schema);
        assertEquals(new TreeSet<>(A_CORBEILLE), new TreeSet<>(avecDeleted));
        for (String table : A_CORBEILLE) {
            assertEquals("uuid", texte(c, "SELECT data_type FROM information_schema.columns"
                    + " WHERE table_schema = ? AND table_name = ? AND column_name = 'supprime_par'", schema, table));
            assertEquals("timestamp with time zone", texte(c, "SELECT data_type FROM information_schema.columns"
                    + " WHERE table_schema = ? AND table_name = ? AND column_name = 'supprime_le'", schema, table));
        }
    }

    /** document.metadonnees : jsonb, NOT NULL, objet vide par défaut, index GIN. */
    private void verifierMetadonnees(Connection c, String schema) throws SQLException {
        assertEquals("jsonb", texte(c, "SELECT data_type FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = 'document' AND column_name = 'metadonnees'", schema));
        assertEquals("NO", texte(c, "SELECT is_nullable FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = 'document' AND column_name = 'metadonnees'", schema));
        String index = texte(c, "SELECT indexdef FROM pg_indexes WHERE schemaname = ?"
                + " AND indexname = 'idx_document_metadonnees'", schema);
        assertTrue(index != null && index.contains("USING gin (metadonnees)"), "index GIN attendu : " + index);
    }

    /* ---------------------------------------------------------------- outillage */

    private Liquibase liquibase(Connection c, String schema) throws Exception {
        executer(c, "SET search_path TO " + schema);
        Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
        db.setDefaultSchemaName(schema);
        db.setLiquibaseSchemaName(schema);
        return new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), db);
    }

    private static Set<String> tables(Connection c, String schema) throws SQLException {
        return new TreeSet<>(lignes(c, "SELECT table_name FROM information_schema.tables"
                + " WHERE table_schema = ? AND table_type = 'BASE TABLE'", schema));
    }

    /**
     * Liquibase passe la connexion en mode transactionnel (autocommit coupé) :
     * sans validation explicite, la suppression du schéma jetable était
     * annulée à la fermeture et les schémas s'accumulaient dans la base de test.
     */
    private static void supprimerSchema(Connection c, String schema) throws SQLException {
        if (!c.getAutoCommit()) c.rollback();
        executer(c, "DROP SCHEMA " + schema + " CASCADE");
        if (!c.getAutoCommit()) c.commit();
    }

    private static void executer(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static int compter(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getInt(1);
        }
    }

    private static String texte(Connection c, String sql, String... params) throws SQLException {
        List<String> l = lignes(c, sql, params);
        return l.isEmpty() ? null : l.get(0);
    }

    private static List<String> lignes(Connection c, String sql, String... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setString(i + 1, params[i]);
            try (ResultSet r = ps.executeQuery()) {
                List<String> l = new ArrayList<>();
                while (r.next()) l.add(r.getString(1));
                return l;
            }
        }
    }
}
