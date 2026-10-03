package com.ipt.ged.common;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.cache.ContextCacheUtils;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La suite de tests tient dans les connexions du serveur PostgreSQL de la CI.
 *
 * <p>Spring garde en cache chaque contexte de test distinct, avec son pool Hikari ouvert.
 * Avec les réglages par défaut (32 contextes en cache, pools de 10 connexions gardées
 * ouvertes), les pools dépassaient les 97 connexions qu'un rôle non superutilisateur
 * obtient du service {@code postgres:16} de la CI ({@code max_connections} 100 dont 3
 * réservées) : « remaining connection slots are reserved for roles with the SUPERUSER
 * attribute » dès le dixième contexte (journal du service, exécutions du 01/10 au 03/10) :
 * ce pool ne peut plus grandir, et un test qui lui demanderait une connexion de plus
 * attendrait 30 s puis échouerait ; au onzième contexte, plus aucune connexion.
 *
 * <p>Invariant vérifié : nombre maximal de contextes en cache × taille maximale d'un pool,
 * plus une marge pour les connexions hors pool (Liquibase au démarrage d'un contexte,
 * connexions directes de certains tests, supervision), tient sous la limite du serveur ;
 * et un contexte inactif ne garde qu'une connexion.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BudgetConnexionsSuiteTest {

    /** Connexions ouvertes hors des pools : Liquibase, connexions JDBC directes des tests, psql. */
    static final int MARGE_HORS_POOLS = 15;

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("Contextes en cache × taille de pool + marge ≤ connexions accordées par le serveur à ged_app")
    void poolsTiennentDansLeServeur() throws Exception {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        int contextes = ContextCacheUtils.retrieveMaxCacheSize();
        int parPool = pool.getMaximumPoolSize();
        int serveur = Integer.parseInt(jdbc.queryForObject("SHOW max_connections", String.class))
                - Integer.parseInt(jdbc.queryForObject("SHOW superuser_reserved_connections", String.class))
                - Integer.parseInt(jdbc.queryForObject("SHOW reserved_connections", String.class));
        int besoin = contextes * parPool + MARGE_HORS_POOLS;
        assertTrue(besoin <= serveur, contextes + " contextes en cache × " + parPool + " connexions par pool + "
                + MARGE_HORS_POOLS + " = " + besoin + " connexions possibles, le serveur n'en accorde que " + serveur
                + " : réduire spring.test.context.cache.maxSize (spring.properties des tests) ou"
                + " spring.datasource.hikari.maximum-pool-size (application-test.yml)");
    }

    @Test
    @DisplayName("Un contexte en cache inactif rend ses connexions (au plus une gardée ouverte)")
    void contexteInactifRendSesConnexions() throws Exception {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        assertTrue(pool.getMinimumIdle() <= 1, "minimum-idle = " + pool.getMinimumIdle()
                + " : chaque contexte en cache garderait autant de connexions ouvertes");
        assertTrue(pool.getIdleTimeout() > 0 && pool.getIdleTimeout() <= 60_000,
                "idle-timeout = " + pool.getIdleTimeout() + " ms : connexions inactives rendues trop tard");
    }
}
