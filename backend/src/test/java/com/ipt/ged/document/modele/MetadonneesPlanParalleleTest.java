package com.ipt.ged.document.modele;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.autorisation.Confidentialite;
import com.ipt.ged.index.IndexField;
import com.ipt.ged.index.IndexFieldType;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.support.JeuDroits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ANO-E7-007 (§12.7, T-104) : la lecture typée des métadonnées ({@code meta_date},
 * {@code meta_nombre}) doit tenir dans un plan PARALLÈLE.
 *
 * <p>{@code meta_date} était déclarée {@code PARALLEL SAFE} avec un bloc
 * {@code EXCEPTION} (sous-transaction, interdite en mode parallèle) : sur un fonds
 * de 100 000 documents, {@code POST /documents/recherche} avec un critère de date
 * répondait 500 et l'index d'expression de {@code DEPLOIEMENT.md} §8 ne se créait
 * pas. Un fonds de cette taille n'est pas nécessaire pour le prouver : les coûts du
 * parallélisme ramenés à zéro font choisir le même plan à PostgreSQL sur quelques
 * lignes (ce que vérifie l'{@code EXPLAIN}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithUserDetails(Comptes.ADMIN)
class MetadonneesPlanParalleleTest {

    /** Réglages qui font choisir un plan parallèle quelle que soit la taille de la table. */
    private static final String[] PLAN_PARALLELE = {
            "SET LOCAL max_parallel_workers_per_gather = 2",
            "SET LOCAL parallel_setup_cost = 0",
            "SET LOCAL parallel_tuple_cost = 0",
            "SET LOCAL min_parallel_table_scan_size = 0",
            "SET LOCAL min_parallel_index_scan_size = 0"};

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JeuDroits jeu;

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.liquibase.user}")
    private String proprietaire;

    @Value("${spring.liquibase.password:}")
    private String motDePasseProprietaire;

    private UUID type;
    private String codeDate;
    private String codeNombre;

    /**
     * Douze documents du même type : six dates valides, cinq valeurs qui n'en sont
     * pas (forme française, 30 février, 29 février non bissextile, an 0000, mois 13),
     * un document sans la métadonnée. Les nombres comptent deux chaînes illisibles,
     * dont une de forme numérique hors des limites du type numeric. Écrites en SQL,
     * comme une reprise, puisque l'API refuse ces valeurs.
     */
    @BeforeEach
    void fonds() {
        type = jeu.type(jeu.noeud("ANO-E7-007 " + UUID.randomUUID().toString().substring(0, 8), null),
                Confidentialite.PUBLIC);
        IndexField date = jeu.index("PAR_DATE", IndexFieldType.DATE, false, null);
        IndexField nombre = jeu.index("PAR_NOMBRE", IndexFieldType.NOMBRE, false, null);
        jeu.planPour(type, date, nombre);
        codeDate = date.getCode();
        codeNombre = nombre.getCode();
        String[][] valeurs = {
                {"\"2021-01-10\"", "100"}, {"\"2022-06-30\"", "2500.5"}, {"\"2023-03-05\"", "-3"},
                {"\"2024-02-29\"", "\"1e1000000\""}, {"\"2025-12-31\"", "\"12 500,00\""}, {"\"2026-09-01\"", "1e5"},
                {"\"31/12/2026\"", null}, {"\"2026-02-30\"", null}, {"\"2023-02-29\"", null},
                {"\"0000-01-01\"", null}, {"\"2026-13-01\"", null}, {null, null}};
        for (String[] v : valeurs) {
            UUID d = jeu.document("ano-e7-007", type, Confidentialite.PUBLIC, null);
            String json = "{" + (v[0] == null ? "" : "\"" + codeDate + "\":" + v[0])
                    + (v[1] == null ? "" : ",\"" + codeNombre + "\":" + v[1]) + "}";
            jdbc.update("UPDATE document SET metadonnees = CAST(? AS jsonb) WHERE id = ?", json, d);
        }
    }

    private long total(String critere) throws Exception {
        String corps = "{\"typeDocumentId\":\"" + type + "\",\"criteres\":[" + critere + "]}";
        JsonNode r = om.readTree(mvc.perform(post("/api/v1/documents/recherche").contentType(APPLICATION_JSON)
                .content(corps)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return r.get("total").asLong();
    }

    @Test
    @Transactional
    @DisplayName("POST /documents/recherche, plan parallèle : plage de date large, étroite, « renseignée » et nombre en 200")
    void rechercheEnPlanParallele() throws Exception {
        // Même transaction (et même connexion) que la requête MockMvc : les réglages s'y appliquent.
        for (String s : PLAN_PARALLELE) jdbc.execute(s);
        String plan = String.join("\n", jdbc.queryForList("EXPLAIN SELECT count(*) FROM document d WHERE"
                + " d.type_document_id = '" + type + "' AND meta_date(d.metadonnees, '" + codeDate + "')"
                + " >= DATE '2022-01-01'", String.class));
        assertTrue(plan.contains("Gather"), "le critère de date doit être lu par un plan parallèle :\n" + plan);

        // Plage large (« de » seul) : 500 avant la correction.
        assertEquals(5, total("{\"code\":\"" + codeDate + "\",\"de\":\"2022-01-01\"}"));
        assertEquals(6, total("{\"code\":\"" + codeDate + "\"}"), "renseignée : dates valides seulement");
        assertEquals(1, total("{\"code\":\"" + codeDate + "\",\"de\":\"2024-02-01\",\"a\":\"2024-03-01\"}"));
        // Nombre : la chaîne hors limites de numeric n'interrompt plus la requête.
        assertEquals(3, total("{\"code\":\"" + codeNombre + "\",\"de\":\"0\"}"));
        assertEquals(4, total("{\"code\":\"" + codeNombre + "\"}"));
    }

    @Test
    @DisplayName("Index d'expression de DEPLOIEMENT.md §8 : construction parallèle sans erreur, puis employé en plan parallèle")
    void indexDeDeploiementSeCree() throws Exception {
        try (Connection c = DriverManager.getConnection(url, proprietaire, motDePasseProprietaire)) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL max_parallel_maintenance_workers = 2");
                s.execute("SET LOCAL maintenance_work_mem = '256MB'");
                for (String r : PLAN_PARALLELE) s.execute(r);
                // Tels que DEPLOIEMENT.md §8 les écrit (le code du champ en constante).
                s.execute("CREATE INDEX idx_document_meta_date_ano_e7_007 ON document (meta_date(metadonnees, '"
                        + codeDate + "'))");
                s.execute("CREATE INDEX idx_document_meta_nombre_ano_e7_007 ON document (meta_nombre(metadonnees, '"
                        + codeNombre + "'))");
                s.execute("ANALYZE document");
                String large = "SELECT count(*) FROM document d WHERE d.type_document_id = '" + type
                        + "' AND meta_date(d.metadonnees, '" + codeDate + "') >= DATE '2022-01-01'";
                assertTrue(lignes(s, "EXPLAIN " + large).contains("Gather"));
                assertEquals("5", lignes(s, large));
                assertEquals("1", lignes(s, "SELECT count(*) FROM document d WHERE meta_date(d.metadonnees, '"
                        + codeDate + "') BETWEEN DATE '2024-02-01' AND DATE '2024-03-01'"));
                assertEquals("3", lignes(s, "SELECT count(*) FROM document d WHERE meta_nombre(d.metadonnees, '"
                        + codeNombre + "') >= 0"));
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("Aucune fonction PARALLEL SAFE du schéma n'ouvre de sous-transaction ; meta_* immuables et sûres")
    void fonctionsSuresEnParallele() {
        List<String> fautives = jdbc.queryForList("SELECT p.proname FROM pg_proc p JOIN pg_language l ON l.oid = p.prolang"
                + " WHERE p.pronamespace = current_schema()::regnamespace AND p.proparallel = 's'"
                + " AND l.lanname = 'plpgsql' AND p.prosrc ~* '\\mexception\\s+when\\M'", String.class);
        assertEquals(List.of(), fautives, "bloc EXCEPTION dans une fonction PARALLEL SAFE");
        assertEquals("meta_date=i/s meta_nombre=i/s meta_texte=i/s", jdbc.queryForObject(
                "SELECT string_agg(proname || '=' || provolatile::text || '/' || proparallel::text, ' ' ORDER BY proname) FROM pg_proc"
                        + " WHERE pronamespace = current_schema()::regnamespace AND proname LIKE 'meta\\_%'", String.class));
    }

    @Test
    @DisplayName("Même sémantique qu'avant : chaque valeur lue à l'identique, toute autre en NULL, jamais d'erreur")
    void memeSemantique() throws Exception {
        try (Connection c = DriverManager.getConnection(url, proprietaire, motDePasseProprietaire);
             Statement s = c.createStatement()) {
            s.execute("SET max_parallel_workers_per_gather = 0");
            // Références : les corps d'origine (202609301020-1), bloc EXCEPTION compris, hors plan parallèle.
            s.execute("CREATE FUNCTION pg_temp.ref_date(m jsonb, code text) RETURNS date LANGUAGE plpgsql AS $f$"
                    + " DECLARE v text := m ->> code; BEGIN"
                    + " IF v IS NULL OR v !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' THEN RETURN NULL; END IF;"
                    + " RETURN make_date(substr(v, 1, 4)::int, substr(v, 6, 2)::int, substr(v, 9, 2)::int);"
                    + " EXCEPTION WHEN others THEN RETURN NULL; END $f$");
            s.execute("CREATE FUNCTION pg_temp.ref_nombre(m jsonb, code text) RETURNS numeric LANGUAGE plpgsql AS $f$"
                    + " DECLARE v text := m ->> code; BEGIN"
                    + " IF v IS NULL OR v !~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$' THEN RETURN NULL; END IF;"
                    + " RETURN v::numeric; EXCEPTION WHEN others THEN RETURN NULL; END $f$");

            // Toutes les chaînes AAAA-MM-JJ de onze années (bissextiles ou non, an 0000), mois 00 à 13,
            // jours 00 à 32, et des formes voisines.
            String dates = "WITH a(an) AS (VALUES (0),(1),(4),(100),(1582),(1600),(1900),(2000),(2023),(2024),(2100),(9999)),"
                    + " v AS (SELECT jsonb_build_object('x', lpad(an::text, 4, '0') || '-' || lpad(mo::text, 2, '0') || '-'"
                    + " || lpad(j::text, 2, '0')) m FROM a, generate_series(0, 13) mo, generate_series(0, 32) j"
                    + " UNION ALL SELECT jsonb_build_object('x', t) FROM unnest(ARRAY['31/12/2026', '2026-2-03',"
                    + " '2026-02-03 ', ' 2026-02-03', '20260203', '', '٢٠٢٦-٠٢-٠٣']) t"
                    + " UNION ALL VALUES ('{\"x\":20260203}'::jsonb), ('{\"x\":true}'), ('[1]'), ('{}'), (NULL))"
                    + " SELECT count(*) || '/' || count(*) FILTER (WHERE meta_date(m, 'x') IS DISTINCT FROM pg_temp.ref_date(m, 'x'))"
                    + " || '/' || count(meta_date(m, 'x')) FROM v";
            assertEquals("5556/0/4019", lignes(s, dates), "chaînes / écarts / dates lues");

            // Nombres : bornes du type numeric (131 072 chiffres avant la virgule, 16 383 après,
            // exposant de ± 1 073 741 823) de part et d'autre, formes illisibles.
            String nombres = "WITH v(t) AS (VALUES ('1e1000000'),('1e131071'),('1e131072'),('10e131071'),('1e-16383'),"
                    + "('1e-16384'),('1e-1000000'),('0e-16383'),('0e-16384'),('0e1073741823'),('0e1073741824'),"
                    + "('0e-1073741823'),('1e+0000000000000005'),('1E5'),('-1.5e-3'),('-0'),('00012.3400'),"
                    + "('0.000001e131078'),('0.000001e131079'),('0.000001e131077'),('0.000e999999999'),('12 500,00'),"
                    + "('1.'),('.5'),('+1'),('1e'),(''),(repeat('9', 131072)),(repeat('9', 131073)),"
                    + "(repeat('0', 200000) || '1'),('0.' || repeat('1', 16383)),('0.' || repeat('1', 16384)),"
                    + "('1.5e-16383'),('1.5e-16382'),('0.' || repeat('0', 16383)),('0.' || repeat('0', 16384)),"
                    + "('1e99999999999'),('1e-99999999999')),"
                    + " m AS (SELECT jsonb_build_object('x', t) m FROM v UNION ALL VALUES ('{\"x\":1250.5}'::jsonb),"
                    + " ('{\"x\":-3e2}'), ('{}'), (NULL))"
                    + " SELECT count(*) || '/' || count(*) FILTER (WHERE meta_nombre(m, 'x') IS DISTINCT FROM pg_temp.ref_nombre(m, 'x'))"
                    + " || '/' || count(meta_nombre(m, 'x')) FROM m";
            assertEquals("42/0/18", lignes(s, nombres), "valeurs / écarts / nombres lus");
        }
    }

    /** Résultat d'une requête, lignes jointes par un saut de ligne. */
    private static String lignes(Statement s, String sql) throws Exception {
        List<String> l = new ArrayList<>();
        try (ResultSet r = s.executeQuery(sql)) {
            while (r.next()) l.add(r.getString(1));
        }
        return String.join("\n", l);
    }
}
