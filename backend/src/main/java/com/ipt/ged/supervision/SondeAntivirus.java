package com.ipt.ged.supervision;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Sonde {@code antivirus} : le démon ClamAV ({@code clamd}) répond à
 * {@code zPING} par {@code PONG} (DAT 6.7).
 *
 * <p>Enjeu : l'antivirus est en échec fermé (DAT 6.1.5), un clamd tombé bloque
 * TOUS les dépôts. La sonde doit donc le signaler avant les utilisateurs.
 *
 * <p>Protocole clamd : commande préfixée par {@code z} et terminée par un octet
 * nul, réponse terminée par un octet nul.
 */
@Component("antivirusHealthIndicator")
public class SondeAntivirus implements HealthIndicator {

    private static final int REPONSE_MAX = 64;

    private final boolean actif;
    private final String hote;
    private final int port;
    private final int delaiMs;

    public SondeAntivirus(@Value("${ged.supervision.antivirus.actif:false}") boolean actif,
                          @Value("${ged.supervision.antivirus.hote:127.0.0.1}") String hote,
                          @Value("${ged.supervision.antivirus.port:3310}") int port,
                          @Value("${ged.supervision.antivirus.delai-ms:3000}") int delaiMs) {
        this.actif = actif;
        this.hote = hote;
        this.port = port;
        this.delaiMs = delaiMs;
    }

    @Override
    public Health health() {
        if (!actif) {
            return Health.up().withDetail("supervision", "désactivée (ged.supervision.antivirus.actif)").build();
        }
        String cible = hote + ":" + port;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(hote, port), delaiMs);
            socket.setSoTimeout(delaiMs);
            socket.getOutputStream().write("zPING\0".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String reponse = lireJusquAuNul(socket.getInputStream());
            if ("PONG".equals(reponse)) {
                return Health.up().withDetail("clamd", cible).build();
            }
            return Health.down().withDetail("clamd", cible)
                    .withDetail("anomalie", "réponse inattendue").build();
        } catch (IOException e) {
            return Health.down().withDetail("clamd", cible)
                    .withDetail("anomalie", e.getClass().getSimpleName()).build();
        }
    }

    private static String lireJusquAuNul(InputStream in) throws IOException {
        ByteArrayOutputStream tampon = new ByteArrayOutputStream();
        int octet;
        while ((octet = in.read()) > 0 && tampon.size() < REPONSE_MAX) {
            tampon.write(octet);
        }
        return tampon.toString(StandardCharsets.US_ASCII).trim();
    }
}
