package com.ipt.ged.fichier.cles;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dépôt JDBC de {@code cle_fichier}, sur PostgreSQL : la table reproduit le
 * changeset {@code 202609261200_cle_fichier.xml} dans un schéma <b>jetable</b>
 * de la base de test, créé avec le compte propriétaire {@code ged_owner} et
 * supprimé après chaque test (même principe que {@code SchemaLiquibaseTest}).
 * Le changeset n'est pas encore dans le changelog maître : il sera branché avec
 * le stockage chiffré, et ce test deviendra alors un test du schéma réel.
 *
 * <p>Prérequis : base de test préparée avec {@code preparer-base.sql -v tests=oui}.
 * Connexion par les mêmes variables que le profil {@code test} : {@code DB_HOST},
 * {@code DB_PORT}, {@code DB_NAME_TEST} (sinon {@code <DB_NAME>_test}),
 * {@code DB_OWNER_USER}, {@code DB_OWNER_PASSWORD}.
 */
class DepotClesFichierJdbcTest {

    private JdbcTemplate proprietaire;
    private JdbcTemplate jdbc;
    private DepotClesFichierJdbc depot;
    private String schema;

    private static String env(String nom, String defaut) {
        Map<String, String> e = System.getenv();
        String v = e.get(nom);
        return v == null || v.isBlank() ? defaut : v;
    }

    private static DriverManagerDataSource source(String schemaCourant) {
        String base = env("DB_NAME_TEST", env("DB_NAME", "ged_dev1") + "_test");
        String url = "jdbc:postgresql://" + env("DB_HOST", "localhost") + ":" + env("DB_PORT", "5432")
                + "/" + base + (schemaCourant == null ? "" : "?currentSchema=" + schemaCourant);
        return new DriverManagerDataSource(url, env("DB_OWNER_USER", "ged_owner"), env("DB_OWNER_PASSWORD", ""));
    }

    @BeforeEach
    void preparer() {
        schema = "ged_verif_cles_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        proprietaire = new JdbcTemplate(source(null));
        proprietaire.execute("CREATE SCHEMA " + schema);
        jdbc = new JdbcTemplate(source(schema));
        jdbc.execute("""
                CREATE TABLE cle_fichier (
                    id uuid NOT NULL CONSTRAINT pk_cle_fichier PRIMARY KEY,
                    dek_enveloppee bytea NOT NULL,
                    kek_id varchar(64) NOT NULL,
                    algorithme varchar(32) DEFAULT 'AES-256-GCM' NOT NULL,
                    cree_le timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                    modifie_le timestamp with time zone,
                    CONSTRAINT ck_cle_fichier_algorithme CHECK (algorithme IN ('AES-256-GCM'))
                )""");
        jdbc.execute("CREATE INDEX idx_cle_fichier_kek_id ON cle_fichier (kek_id)");
        depot = new DepotClesFichierJdbc(jdbc);
    }

    @AfterEach
    void fermer() {
        proprietaire.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    private static CleFichier cle(UUID id, String kek) {
        return new CleFichier(id, new byte[]{1, 2, 3, (byte) id.hashCode()}, kek, CleFichier.AES_256_GCM);
    }

    @Test
    @DisplayName("Enregistrer, retrouver, supprimer (destruction cryptographique)")
    void cycle() {
        UUID id = UUID.randomUUID();
        depot.enregistrer(cle(id, "kek-00001"));
        CleFichier lue = depot.trouver(id).orElseThrow();
        assertEquals(id, lue.id());
        assertArrayEquals(cle(id, "x").dekEnveloppee(), lue.dekEnveloppee());
        assertEquals("kek-00001", lue.kekId());
        assertEquals("AES-256-GCM", lue.algorithme());
        assertTrue(depot.supprimer(id));
        assertTrue(depot.trouver(id).isEmpty());
        assertFalse(depot.supprimer(id));
    }

    @Test
    @DisplayName("Une seule clé par fichier ; algorithme contraint")
    void contraintes() {
        UUID id = UUID.randomUUID();
        depot.enregistrer(cle(id, "kek-00001"));
        assertThrows(RuntimeException.class, () -> depot.enregistrer(cle(id, "kek-00001")));
        assertThrows(RuntimeException.class,
                () -> depot.enregistrer(new CleFichier(UUID.randomUUID(), new byte[4], "kek-00001", "DES")));
    }

    @Test
    @DisplayName("Lots hors KEK active, paginés par identifiant")
    void lots() {
        for (int i = 0; i < 25; i++) depot.enregistrer(cle(UUID.randomUUID(), i % 5 == 0 ? "kek-00002" : "kek-00001"));
        List<UUID> vus = new ArrayList<>();
        UUID curseur = null;
        List<CleFichier> lot;
        do {
            lot = depot.lotHorsKek("kek-00002", curseur, 7);
            for (CleFichier c : lot) {
                assertEquals("kek-00001", c.kekId());
                vus.add(c.id());
                curseur = c.id();
            }
        } while (lot.size() == 7);
        assertEquals(20, vus.size());
        assertEquals(20, vus.stream().distinct().count());
        assertEquals(20, depot.compterParKek("kek-00001"));
        assertEquals(5, depot.compterParKek("kek-00002"));
    }

    @Test
    @DisplayName("Remplacement d'enveloppe conditionnel à l'ancienne KEK")
    void remplacement() {
        UUID id = UUID.randomUUID();
        depot.enregistrer(cle(id, "kek-00001"));
        CleEnveloppee nouvelle = new CleEnveloppee("kek-00002", new byte[]{9, 9});
        assertFalse(depot.remplacerEnveloppe(id, "kek-00003", nouvelle), "ancienne KEK erronée : aucune mise à jour");
        assertTrue(depot.remplacerEnveloppe(id, "kek-00001", nouvelle));
        CleFichier lue = depot.trouver(id).orElseThrow();
        assertEquals("kek-00002", lue.kekId());
        assertArrayEquals(new byte[]{9, 9}, lue.dekEnveloppee());
        assertNotNull(jdbc.queryForObject("SELECT modifie_le FROM cle_fichier WHERE id = ?", Object.class, id));
    }
}
