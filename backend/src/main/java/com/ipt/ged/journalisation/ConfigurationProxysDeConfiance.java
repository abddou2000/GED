package com.ipt.ged.journalisation;

import org.apache.catalina.valves.RemoteIpValve;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Une seule règle de confiance envers les proxys, pour tout le back-end.
 *
 * <p>Avec {@code server.forward-headers-strategy: native}, Tomcat
 * ({@link RemoteIpValve}) remplace l'adresse distante par celle de
 * {@code X-Forwarded-For} pour une requête venue d'un « proxy interne ». Sa
 * liste par défaut couvre tous les réseaux privés (10/8, 192.168/16…) :
 * n'importe quelle machine du réseau de MMED pourrait alors choisir l'adresse
 * que verraient la limitation des connexions et le journal. On la remplace par
 * la liste de {@code ged.journalisation.proxys-de-confiance} (l'adresse de
 * NGINX seulement), celle qu'applique aussi {@link FiltreContexteRequete}.
 *
 * <p>Tomcat attend une expression régulière : les adresses exactes et les
 * plages IPv4 alignées sur l'octet (/8, /16, /24, /32) sont traduites ; toute
 * autre plage arrête le démarrage plutôt que d'être approchée.
 */
@Configuration
public class ConfigurationProxysDeConfiance {

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> proxysDeConfianceTomcat(
            @Value("${ged.journalisation.proxys-de-confiance:127.0.0.1,::1}") List<String> proxys) {
        String expression = expressionTomcat(proxys);
        return fabrique -> fabrique.getEngineValves().stream()
                .filter(RemoteIpValve.class::isInstance)
                .map(RemoteIpValve.class::cast)
                .forEach(valve -> valve.setInternalProxies(expression));
    }

    /** Liste d'adresses ou de plages → expression régulière de {@code RemoteIpValve#internalProxies}. */
    static String expressionTomcat(List<String> proxys) {
        List<String> parties = new ArrayList<>();
        for (String brut : proxys) {
            String entree = brut.trim();
            if (entree.isEmpty()) continue;
            int barre = entree.indexOf('/');
            if (barre < 0) {
                parties.add(normaliser(entree).replace(".", "\\."));
                continue;
            }
            String adresse = entree.substring(0, barre);
            int prefixe = Integer.parseInt(entree.substring(barre + 1));
            String[] octets = normaliser(adresse).split("\\.");
            if (octets.length != 4 || prefixe % 8 != 0 || prefixe < 8 || prefixe > 32) {
                throw new IllegalStateException("Proxy de confiance « " + entree + " » : seules les adresses "
                        + "exactes et les plages IPv4 /8, /16, /24 ou /32 sont acceptées.");
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                if (i > 0) sb.append("\\.");
                sb.append(i < prefixe / 8 ? octets[i] : "\\d{1,3}");
            }
            parties.add(sb.toString());
        }
        if (parties.isEmpty()) {
            // Aucun proxy déclaré : aucune adresse n'est crue, l'en-tête est ignoré.
            return "^$";
        }
        return String.join("|", parties);
    }

    /* Forme textuelle que Tomcat compare (getRemoteAddr) : IPv6 développée. */
    private static String normaliser(String adresse) {
        // Adresse littérale seulement : un nom d'hôte déclencherait une résolution DNS.
        if (!adresse.matches("^[0-9A-Fa-f:.]+$")) {
            throw new IllegalStateException("Proxy de confiance « " + adresse + " » : adresse IP attendue.");
        }
        try {
            return InetAddress.getByName(adresse).getHostAddress();
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Proxy de confiance illisible : « " + adresse + " »", e);
        }
    }
}
