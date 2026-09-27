package com.ipt.ged.supervision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.server.WebServer;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * La chaîne ouverte du management ne s'applique qu'aux requêtes arrivées sur
 * le port de management effectif ; l'API publique n'est jamais concernée.
 */
class SecuritePortManagementTest {

    private static WebServerInitializedEvent demarrage(String espace, int port) {
        WebServer serveur = mock(WebServer.class);
        when(serveur.getPort()).thenReturn(port);
        WebServerApplicationContext contexte = mock(WebServerApplicationContext.class);
        when(contexte.getServerNamespace()).thenReturn(espace);
        return new WebServerInitializedEvent(serveur) {
            @Override
            public WebServerApplicationContext getApplicationContext() {
                return contexte;
            }
        };
    }

    private static MockHttpServletRequest surPort(int port) {
        var r = new MockHttpServletRequest("GET", "/actuator/prometheus");
        r.setLocalPort(port);
        return r;
    }

    @Test
    @DisplayName("Avant le démarrage du serveur de management, aucune requête n'est concernée")
    void avantDemarrage() {
        assertThat(new SecuritePortManagement().surPortManagement(surPort(8081))).isFalse();
    }

    @Test
    @DisplayName("Seul le port du serveur « management » ouvre la chaîne, jamais celui de l'API")
    void seulLePortDeManagement() {
        var securite = new SecuritePortManagement();
        securite.serveurDemarre(demarrage(null, 8080));            // serveur principal (API)
        securite.serveurDemarre(demarrage("management", 18092));   // serveur de management

        assertThat(securite.surPortManagement(surPort(18092))).isTrue();
        assertThat(securite.surPortManagement(surPort(8080))).isFalse();
    }
}
