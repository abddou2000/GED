package com.ipt.ged.journalisation;

import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tomcat ne croit {@code X-Forwarded-For} que des proxys déclarés pour le
 * journal : même règle pour la limitation des connexions et pour les journaux.
 */
class ProxysDeConfianceTest {

    @Test
    @DisplayName("Adresses exactes et plages IPv4 alignées traduites ; le reste du réseau n'est pas cru")
    void expression() {
        Pattern p = Pattern.compile(ConfigurationProxysDeConfiance.expressionTomcat(
                List.of("10.0.0.10", "::1", "192.168.5.0/24")));
        assertThat(p.matcher("10.0.0.10").matches()).isTrue();
        assertThat(p.matcher("0:0:0:0:0:0:0:1").matches()).isTrue();
        assertThat(p.matcher("192.168.5.77").matches()).isTrue();
        // Voisins et réseaux privés que la liste par défaut de Tomcat croirait.
        assertThat(p.matcher("10.0.0.11").matches()).isFalse();
        assertThat(p.matcher("10.0.0.100").matches()).isFalse();
        assertThat(p.matcher("192.168.6.1").matches()).isFalse();
        assertThat(p.matcher("172.16.0.1").matches()).isFalse();
    }

    @Test
    @DisplayName("Plage non alignée ou nom d'hôte : refus au démarrage")
    void refus() {
        assertThatThrownBy(() -> ConfigurationProxysDeConfiance.expressionTomcat(List.of("10.0.0.0/20")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ConfigurationProxysDeConfiance.expressionTomcat(List.of("nginx.local")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(ConfigurationProxysDeConfiance.expressionTomcat(List.of())).isEqualTo("^$");
    }

    @Test
    @DisplayName("La valve RemoteIpValve de Tomcat reçoit la liste des proxys de confiance")
    void valve() {
        TomcatServletWebServerFactory fabrique = new TomcatServletWebServerFactory();
        RemoteIpValve valve = new RemoteIpValve();
        fabrique.addEngineValves(valve);
        new ConfigurationProxysDeConfiance().proxysDeConfianceTomcat(List.of("10.0.0.10")).customize(fabrique);
        assertThat(valve.getInternalProxies()).isEqualTo("10\\.0\\.0\\.10");
    }
}
