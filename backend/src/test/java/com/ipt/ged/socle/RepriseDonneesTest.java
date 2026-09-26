package com.ipt.ged.socle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reprise des données de l'ancien schéma (MySQL, identifiants numériques) vers
 * le schéma PostgreSQL à clés UUID : exécute les scripts livrés dans
 * {@code scripts/reprise/} sur un export produit au format de
 * {@code exporter-mysql.sh} ({@code src/test/resources/reprise/jeu-essai}).
 *
 * <p>Le jeu d'essai contient les cas qui cassent une reprise naïve : enfant
 * dont l'identifiant précède celui de son parent, horodatages égaux ou absents,
 * texte arabe, tabulations, sauts de ligne et barres obliques inverses, valeurs
 * NULL, lignes en corbeille, charte de nommage à jetons numériques, charte
 * illisible.
 *
 * <p>Le schéma cible est un schéma jetable créé par Liquibase ; le transit
 * {@code reprise_source} est supprimé en fin de test.
 */
@SpringBootTest
@ActiveProfiles("test")
class RepriseDonneesTest {

    private static final Path SCRIPTS = Path.of("scripts", "reprise");
    private static final Path JEU = Path.of("src", "test", "resources", "reprise", "jeu-essai");

    private static final List<String> TABLES_SOURCE = List.of(
            "employes", "workflow_ged", "workflow_ged_steps", "work_spaces",
            "access_groups", "pivot_workspace_groups", "pivot_employe_groups", "etiquettes", "indices",
            "plan_d_indexations", "pivot_plan_d_indexation_indices", "type_de_documents", "documents_file",
            "document_versions", "pivot_document_etiquettes", "document_index_values",
            "workflow_ged_signatures");

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.liquibase.user}")
    private String proprietaire;

    @Value("${spring.liquibase.password:}")
    private String motDePasse;

    @Test
    @DisplayName("Reprise ancien schéma -> UUID : complète, fidèle, ordonnée, et refusée sur une cible non vide")
    void reprise() throws Exception {
        String cible = "ged_reprise_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(url, proprietaire, motDePasse)) {
            executer(c, "CREATE SCHEMA " + cible);
            try {
                creerSchemaCible(c, cible);
                executer(c, lire("01_schema_source.sql"));
                charger(c);

                transferer(c, cible);

                List<String> ecarts = new ArrayList<>();
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(lire("03_controles.sql"))) {
                    int lignes = 0;
                    while (r.next()) {
                        lignes++;
                        if (!"OK".equals(r.getString("statut"))) {
                            ecarts.add(r.getString("controle") + " : attendu " + r.getLong("attendu")
                                    + ", obtenu " + r.getLong("obtenu"));
                        }
                    }
                    assertTrue(lignes >= 25, "tous les contrôles doivent s'exécuter");
                }
                assertEquals(List.of(), ecarts, "contrôles en écart");

                verifierFidelite(c);
                verifierOrdre(c);
                verifierCharte(c);

                // Rejouer sur une cible déjà remplie est refusé, sans rien écrire.
                SQLException refus = assertThrows(SQLException.class, () -> transferer(c, cible));
                assertTrue(refus.getMessage().contains("Reprise refusée"), refus.getMessage());
                assertEquals(3, compter(c, "SELECT count(*) FROM document"));
            } finally {
                c.setAutoCommit(true);
                executer(c, "DROP SCHEMA IF EXISTS reprise_source CASCADE");
                executer(c, "DROP SCHEMA " + cible + " CASCADE");
            }
        }
    }

    /* ---------------------------------------------------------------- vérifications */

    private void verifierFidelite(Connection c) throws SQLException {
        // Arabe, accents.
        assertEquals("الإدريسي", texte(c, "SELECT last_name FROM employe WHERE first_name = 'عبد الله'"));
        // Tabulation et saut de ligne conservés ; chaîne vide distincte de NULL.
        assertEquals("Pièces\tcomptables\nde l'exercice",
                texte(c, "SELECT description FROM workspace WHERE code = 'WS-FACT'"));
        assertEquals("", texte(c, "SELECT description FROM workspace WHERE code = 'WS-ARCH'"));
        assertNull(texte(c, "SELECT description FROM workspace WHERE code = 'WS-COMPTA'"));
        // Barres obliques inverses.
        assertEquals(1, compter(c, "SELECT count(*) FROM document WHERE name = 'Note C:\\temp\\rapport'"));
        // Parent désigné par un identifiant supérieur au sien.
        assertEquals(texte(c, "SELECT id::text FROM workspace WHERE code = 'WS-COMPTA'"),
                texte(c, "SELECT parent_id::text FROM workspace WHERE code = 'WS-FACT'"));
        // Booléens, corbeille sans auteur connu, métadonnées par défaut.
        assertEquals(1, compter(c, "SELECT count(*) FROM document WHERE is_locked"));
        assertEquals(1, compter(c, "SELECT count(*) FROM document WHERE deleted AND supprime_par IS NULL AND supprime_le IS NULL"));
        assertEquals(3, compter(c, "SELECT count(*) FROM document WHERE metadonnees = '{}'::jsonb"));
        // Taille inconnue ramenée à 0 (colonne désormais NOT NULL).
        assertEquals(0, compter(c, "SELECT size_ko FROM document WHERE file_name = 'rapport.docx'"));
        // Créateur arabe correctement rattaché.
        assertEquals("عبد الله", texte(c, "SELECT e.first_name FROM document d JOIN employe e"
                + " ON e.id = d.created_by_employe_id WHERE d.file_name = 'rapport.docx'"));
        // Horodatage UTC de l'ancienne base -> timestamptz.
        assertEquals(Instant.parse("2026-01-15T10:00:00Z"),
                instant(c, "SELECT created_at FROM document WHERE file_name = 'facture 01.pdf'"));
        // Aucun compte local ni mot de passe n'est repris (lot E2, §3.2).
        assertEquals(0, compter(c, "SELECT count(*) FROM utilisateur"));
        // Associations.
        assertEquals(3, compter(c, "SELECT count(*) FROM access_group_workspace ag JOIN access_group g"
                + " ON g.id = ag.access_group_id WHERE g.code = 'AG-ADMIN'"));
        assertEquals("Haute", texte(c, "SELECT v.valeur FROM document_index_valeur v JOIN index_def i"
                + " ON i.id = v.index_def_id WHERE i.code = 'IDX-PRIO'"));
    }

    /** L'ordre « par id » de l'application reste chronologique après reprise. */
    private void verifierOrdre(Connection c) throws SQLException {
        List<String> employes = lignes(c, "SELECT first_name FROM employe ORDER BY id");
        // Omar n'a pas d'horodatage : il passe en tête ; Sara et Karim partagent
        // le même instant : l'ancien identifiant les départage.
        assertEquals(List.of("Omar", "Sara", "Karim", "Yasmine", "عبد الله"), employes);
        List<String> versions = lignes(c, "SELECT v.file_name FROM version_document v JOIN document d"
                + " ON d.id = v.document_id WHERE d.file_name = 'rapport.docx' ORDER BY v.id DESC");
        assertEquals(List.of("rapport.docx", "rapport-v1.docx"), versions);
        assertEquals(0, compter(c, "SELECT count(*) FROM reprise_source.correspondance"
                + " WHERE substr(id::text, 15, 1) <> '7'"));
    }

    /** Les jetons numériques de la charte deviennent les UUID des index ; le reste est intact. */
    private void verifierCharte(Connection c) throws Exception {
        JsonNode charte = new ObjectMapper().readTree(
                texte(c, "SELECT charte_nommage FROM plan_indexation WHERE code = 'PLAN-FACT'"));
        JsonNode jetons = charte.get("indexs");
        assertEquals(4, jetons.size());
        assertEquals(texte(c, "SELECT id::text FROM index_def WHERE code = 'IDX-DATEEMI'"), jetons.get(0).asText());
        assertEquals("DATE", jetons.get(1).asText());
        assertEquals(texte(c, "SELECT id::text FROM index_def WHERE code = 'IDX-NUMFACT'"), jetons.get(2).asText());
        // « 99 » ne désignait aucun index : conservé tel quel, le trou reste visible.
        assertEquals("99", jetons.get(3).asText());
        assertEquals("_", charte.get("separateur").asText());
        assertFalse(charte.get("majuscule").asBoolean());
        assertEquals("pas du json", texte(c, "SELECT charte_nommage FROM plan_indexation WHERE code = 'PLAN-ABIME'"));
        assertNull(texte(c, "SELECT charte_nommage FROM plan_indexation WHERE code = 'PLAN-MANU'"));
        assertEquals(List.of("IDX-DATEEMI", "IDX-NUMFACT", "IDX-PRIO"), lignes(c, "SELECT i.code FROM plan_index pi"
                + " JOIN index_def i ON i.id = pi.index_def_id ORDER BY pi.position"));
    }

    /* ---------------------------------------------------------------- étapes */

    private void creerSchemaCible(Connection c, String schema) throws Exception {
        executer(c, "SET search_path TO " + schema);
        Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
        db.setDefaultSchemaName(schema);
        db.setLiquibaseSchemaName(schema);
        new Liquibase("db/changelog/db.changelog-master.xml", new ClassLoaderResourceAccessor(), db)
                .update(new Contexts(), new LabelExpression());
    }

    /** Équivalent de la boucle \copy de importer-postgres.sh. */
    private void charger(Connection c) throws Exception {
        CopyManager copie = c.unwrap(PGConnection.class).getCopyAPI();
        for (String table : TABLES_SOURCE) {
            try (Reader r = Files.newBufferedReader(JEU.resolve(table + ".tsv"), StandardCharsets.UTF_8)) {
                copie.copyIn("COPY reprise_source." + table + " FROM STDIN WITH (FORMAT text)", r);
            }
        }
    }

    /** Équivalent de `PGOPTIONS=-c search_path=<cible> psql -1 -f 02_reprise.sql`. */
    private void transferer(Connection c, String cible) throws Exception {
        executer(c, "SET search_path TO " + cible);
        c.setAutoCommit(false);
        try {
            executer(c, lire("02_reprise.sql"));
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    /* ---------------------------------------------------------------- outillage */

    private static String lire(String script) throws Exception {
        return Files.readString(SCRIPTS.resolve(script), StandardCharsets.UTF_8);
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

    private static Instant instant(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getObject(1, java.time.OffsetDateTime.class).toInstant();
        }
    }

    private static String texte(Connection c, String sql) throws SQLException {
        List<String> l = lignes(c, sql);
        return l.isEmpty() ? null : l.get(0);
    }

    private static List<String> lignes(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet r = ps.executeQuery()) {
            List<String> l = new ArrayList<>();
            while (r.next()) l.add(r.getString(1));
            return l;
        }
    }
}
