package com.ipt.ged.fichier.cles;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dépôt JDBC de {@code cle_fichier}, sur une base H2 en mode PostgreSQL dont
 * la table reproduit le changeset {@code 202609261200_cle_fichier.xml}.
 * (Le changeset lui-même a été appliqué et annulé sur PostgreSQL 16, voir le
 * suivi dev3 ; la base de test commune n'a pas encore Liquibase.)
 */
class DepotClesFichierJdbcTest {

    private JdbcTemplate jdbc;
    private DepotClesFichierJdbc depot;

    @BeforeEach
    void preparer() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:cles" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
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
        jdbc.execute("SHUTDOWN");
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
