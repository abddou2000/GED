package com.ipt.ged.securite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Chiffrement des liaisons exigé au démarrage en uat et en prod (DAT §6.2.1, T-065). */
class ControleTransportsChiffresTest {

    @TempDir Path dossier;

    private MockEnvironment conforme(String profil) throws Exception {
        Path ca = Files.writeString(dossier.resolve("postgresql-ca.crt"), "-----BEGIN CERTIFICATE-----");
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profil);
        env.setProperty("ged.base.sslmode", "verify-full");
        env.setProperty("spring.datasource.url", "jdbc:postgresql://db:5432/ged?currentSchema=ged&sslmode=verify-full");
        env.setProperty("spring.datasource.hikari.data-source-properties.sslrootcert", ca.toString());
        env.setProperty("ged.identite.annuaire.exiger-ldaps", "true");
        env.setProperty("ged.identite.annuaire.urls", "ldaps://dc01.marchica.local:636,ldaps://dc02.marchica.local:636");
        env.setProperty("spring.mail.properties.mail.smtp.starttls.required", "true");
        return env;
    }

    @Test
    @DisplayName("Configuration d'exploitation conforme : démarrage accepté (prod et uat)")
    void conforme() throws Exception {
        new ControleTransportsChiffres(conforme("prod")).afterPropertiesSet();
        new ControleTransportsChiffres(conforme("uat")).afterPropertiesSet();
    }

    @Test
    @DisplayName("Affaiblissement par variable d'environnement : démarrage refusé, écarts nommés")
    void affaiblissementsRefuses() throws Exception {
        MockEnvironment env = conforme("prod");
        env.setProperty("ged.base.sslmode", "require");
        env.setProperty("spring.datasource.url", "jdbc:postgresql://db:5432/ged?sslmode=require");
        env.setProperty("spring.datasource.hikari.data-source-properties.sslrootcert", dossier.resolve("absent.crt").toString());
        env.setProperty("ged.identite.annuaire.exiger-ldaps", "false");
        env.setProperty("ged.identite.annuaire.urls", "ldaps://dc01:636,ldap://dc02:389");
        env.setProperty("spring.mail.properties.mail.smtp.starttls.required", "false");
        ControleTransportsChiffres controle = new ControleTransportsChiffres(env);
        assertThat(controle.ecarts()).hasSize(6);
        assertThatThrownBy(controle::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("verify-full").hasMessageContaining("ldap://dc02:389")
                .hasMessageContaining("STARTTLS");
    }

    @Test
    @DisplayName("Dev et test : aucun contrôle (PostgreSQL et annuaire locaux)")
    void horsExploitation() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        env.setProperty("ged.base.sslmode", "prefer");
        assertThat(new ControleTransportsChiffres(env).ecarts()).isEmpty();
    }
}
