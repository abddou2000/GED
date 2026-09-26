package com.ipt.ged.supervision;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p>Si un {@link VerificationAntivirus} est déclaré (client du lot stockage),
 * c'est lui qui répond ; sinon la sonde parle directement à clamd. Protocole :
 * commande préfixée par {@code z} et terminée par un octet nul, réponse
 * terminée par un octet nul.
 *
 * <p>Côté serveur, {@code clamd.conf} doit porter {@code StreamMaxLength 200M} :
 * la valeur par défaut (25 Mo) ferait refuser tout dépôt plus gros
 * (docs/exploitation/EXPLOITATION.md).
 */
@Component("antivirusHealthIndicator")
public class SondeAntivirus implements HealthIndicator {

    private static final int REPONSE_MAX = 64;

    private final boolean actif;
    private final String hote;
    private final int port;
    private final int delaiMs;
    private final VerificationAntivirus externe;

    @Autowired
    public SondeAntivirus(@Value("${ged.supervision.antivirus.actif:false}") boolean actif,
                          @Value("${ged.supervision.antivirus.hote:127.0.0.1}") String hote,
                          @Value("${ged.supervision.antivirus.port:3310}") int port,
                          @Value("${ged.supervision.antivirus.delai-ms:3000}") int delaiMs,
                          ObjectProvider<VerificationAntivirus> externe) {
        this(actif, hote, port, delaiMs, externe.getIfUnique());
    }

    /** @param externe client antivirus à interroger, ou {@code null} pour parler à clamd directement */
    SondeAntivirus(boolean actif, String hote, int port, int delaiMs, VerificationAntivirus externe) {
        this.actif = actif;
        this.hote = hote;
        this.port = port;
        this.delaiMs = delaiMs;
        this.externe = externe;
    }

    @Override
    public Health health() {
        if (!actif) {
            return Health.up().withDetail("supervision", "désactivée (ged.supervision.antivirus.actif)").build();
        }
        if (externe != null) {
            boolean ok;
            try {
                ok = externe.disponible();
            } catch (RuntimeException e) {
                ok = false;
            }
            return (ok ? Health.up() : Health.down().withDetail("anomalie", "moteur injoignable"))
                    .withDetail("source", "client antivirus du dépôt").build();
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
