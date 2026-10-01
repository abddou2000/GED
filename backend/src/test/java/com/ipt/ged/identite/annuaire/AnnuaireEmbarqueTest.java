package com.ipt.ged.identite.annuaire;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O1 (recette vague 9) : après un démarrage refusé en profil dev, la JVM ne
 * s'arrêtait pas. Le fil d'écoute de l'annuaire embarqué (non démon) survivait
 * à la fermeture du contexte : l'annuaire n'était jamais rendu.
 */
class AnnuaireEmbarqueTest {

    private static final String LDIF = "classpath:annuaire/annuaire-dev.ldif";

    @Test
    @DisplayName("Démarrage refusé : la fermeture du contexte arrête l'annuaire embarqué et son fil d'écoute")
    void demarrageRefuseArreteLAnnuaire() throws Exception {
        int port = portLibre();
        AnnotationConfigApplicationContext contexte = new AnnotationConfigApplicationContext();
        contexte.setEnvironment(environnement(port));
        contexte.register(AnnuaireEmbarque.class, ConfigurationRefusee.class);

        assertThatThrownBy(contexte::refresh).isInstanceOf(BeanCreationException.class)
                .hasStackTraceContaining("configuration invalide");

        assertThat(ecoute(port)).as("annuaire encore à l'écoute après le démarrage refusé").isFalse();
        assertThat(filEcouteVivant(port)).as("fil d'écoute LDAP (non démon) encore vivant").isFalse();
    }

    @Test
    @DisplayName("Annuaire partagé entre contextes : arrêté seulement quand le dernier le rend")
    void partageEntreContextes() throws Exception {
        int port = portLibre();
        MockEnvironment env = environnement(port);
        AnnuaireEmbarque premier = new AnnuaireEmbarque(env, new DefaultResourceLoader(), "DC=marchicamed,DC=ma", port, LDIF);
        AnnuaireEmbarque second = new AnnuaireEmbarque(env, new DefaultResourceLoader(), "DC=marchicamed,DC=ma", port, LDIF);
        assertThat(second.simulateur()).isSameAs(premier.simulateur());

        premier.destroy();
        premier.destroy(); // rendu une seule fois
        assertThat(ecoute(port)).as("toujours servi au second contexte").isTrue();

        second.destroy();
        assertThat(ecoute(port)).isFalse();

        // Un contexte suivant en redémarre un.
        AnnuaireEmbarque troisieme = new AnnuaireEmbarque(env, new DefaultResourceLoader(), "DC=marchicamed,DC=ma", port, LDIF);
        assertThat(ecoute(port)).isTrue();
        troisieme.destroy();
        assertThat(ecoute(port)).isFalse();
    }

    /** Une configuration invalide, refusée au démarrage APRÈS l'annuaire embarqué. */
    @Configuration
    static class ConfigurationRefusee {
        @Bean
        Object refus(AnnuaireEmbarque annuaire) {
            throw new IllegalStateException("configuration invalide");
        }
    }

    private static MockEnvironment environnement(int port) {
        MockEnvironment env = new MockEnvironment()
                .withProperty("ged.identite.annuaire.embarque.port", Integer.toString(port))
                .withProperty("ged.identite.annuaire.embarque.ldif", LDIF);
        env.setActiveProfiles("dev");
        return env;
    }

    private static int portLibre() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    private static boolean ecoute(int port) {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Le fil d'UnboundID porte le port dans son nom ; il peut mettre un instant à finir. */
    private static boolean filEcouteVivant(int port) throws InterruptedException {
        String nom = "listening on port " + port;
        for (int i = 0; i < 50; i++) {
            boolean vivant = Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(t -> t.isAlive() && t.getName().contains(nom));
            if (!vivant) return false;
            Thread.sleep(100);
        }
        return true;
    }
}
