package com.ipt.ged.supervision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sondes de santé du DAT 6.7. ClamAV est absent du poste : la sonde antivirus
 * est éprouvée contre un clamd simulé qui parle le protocole réel
 * ({@code zPING\0} / {@code PONG\0}). La sonde de l'annuaire relève du lot
 * identité.
 */
class SondesTest {

    /** Simule clamd : lit une commande terminée par \0 et répond {@code reponse}. */
    private static Thread clamdSimule(ServerSocket serveur, String reponse, StringBuilder commandeRecue) {
        Thread t = new Thread(() -> {
            try (Socket client = serveur.accept()) {
                InputStream in = client.getInputStream();
                int c;
                while ((c = in.read()) > 0) commandeRecue.append((char) c);
                OutputStream out = client.getOutputStream();
                out.write((reponse + "\0").getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException ignoree) {
                // fin du test
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Test
    @DisplayName("Antivirus : clamd répond PONG → UP, la commande envoyée est zPING")
    void antivirusDisponible() throws Exception {
        try (ServerSocket serveur = new ServerSocket(0)) {
            StringBuilder commande = new StringBuilder();
            Thread t = clamdSimule(serveur, "PONG", commande);
            Health h = new SondeAntivirus(true, "127.0.0.1", serveur.getLocalPort(), 2000, (VerificationAntivirus) null).health();
            t.join(2000);
            assertThat(h.getStatus()).isEqualTo(Status.UP);
            assertThat(commande.toString()).isEqualTo("zPING");
        }
    }

    @Test
    @DisplayName("Antivirus : réponse inattendue → DOWN")
    void antivirusReponseInattendue() throws Exception {
        try (ServerSocket serveur = new ServerSocket(0)) {
            clamdSimule(serveur, "UNKNOWN COMMAND", new StringBuilder());
            Health h = new SondeAntivirus(true, "127.0.0.1", serveur.getLocalPort(), 2000, (VerificationAntivirus) null).health();
            assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        }
    }

    @Test
    @DisplayName("Antivirus : clamd injoignable → DOWN (échec fermé visible avant les utilisateurs)")
    void antivirusInjoignable() throws Exception {
        int portLibre;
        try (ServerSocket s = new ServerSocket(0)) {
            portLibre = s.getLocalPort();
        }
        Health h = new SondeAntivirus(true, "127.0.0.1", portLibre, 1000, (VerificationAntivirus) null).health();
        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        assertThat(h.getDetails()).containsKey("anomalie");
    }

    @Test
    @DisplayName("Sondes désactivées : UP, avec la mention explicite de la désactivation")
    void sondesDesactivees() {
        assertThat(new SondeAntivirus(false, "x", 1, 10, (VerificationAntivirus) null).health().getDetails()).containsKey("supervision");
    }

    @Test
    @DisplayName("Antivirus : le client du dépôt, s'il est déclaré, est interrogé à la place de clamd")
    void antivirusClientExterne() {
        assertThat(new SondeAntivirus(true, null, 0, 0, () -> true).health().getStatus()).isEqualTo(Status.UP);
        assertThat(new SondeAntivirus(true, null, 0, 0, () -> false).health().getStatus()).isEqualTo(Status.DOWN);
        VerificationAntivirus enPanne = () -> { throw new IllegalStateException("socket"); };
        assertThat(new SondeAntivirus(true, null, 0, 0, enPanne).health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("Référentiel : répertoire présent et inscriptible → UP avec l'espace disque")
    void referentielDisponible(@TempDir Path racine) {
        Health h = new SondeReferentielFichiers(racine.toString(), 0.999).health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsKeys("octetsLibres", "octetsTotal", "occupation");
    }

    @Test
    @DisplayName("Référentiel : répertoire absent → DOWN")
    void referentielAbsent(@TempDir Path racine) {
        Health h = new SondeReferentielFichiers(racine.resolve("absent").toString(), 0.95).health();
        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("Référentiel : occupation au-delà du seuil critique → DOWN")
    void referentielPlein(@TempDir Path racine) {
        // Seuil nul : tout volume réel est « plein ».
        Health h = new SondeReferentielFichiers(racine.toString(), 0.0).health();
        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
    }

    private static FileDeTraitement file(String nom, long profondeur) {
        return new FileDeTraitement() {
            public String nom() { return nom; }
            public long profondeur() { return profondeur; }
            public Optional<Duration> ageDuPlusAncien() { return Optional.of(Duration.ofSeconds(42)); }
        };
    }

    @Test
    @DisplayName("Files : aucune file déclarée → UP, et l'absence est dite")
    void filesAucune() {
        Health h = new SondeFilesTraitement(List.of(), 10).health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsKey("files");
    }

    @Test
    @DisplayName("Files : profondeur et âge publiés ; débordement ou file illisible → DOWN")
    void filesProfondeur() {
        Health normale = new SondeFilesTraitement(List.of(file("ocr", 3)), 10).health();
        assertThat(normale.getStatus()).isEqualTo(Status.UP);
        assertThat(normale.getDetails().get("ocr"))
                .isEqualTo(Map.of("profondeur", 3L, "agePlusAncienSecondes", 42L));

        assertThat(new SondeFilesTraitement(List.of(file("ocr", 11)), 10).health().getStatus())
                .isEqualTo(Status.DOWN);

        FileDeTraitement enPanne = new FileDeTraitement() {
            public String nom() { return "ocr"; }
            public long profondeur() { throw new IllegalStateException("base indisponible"); }
        };
        assertThat(new SondeFilesTraitement(List.of(enPanne), 10).health().getStatus())
                .isEqualTo(Status.DOWN);
    }
}
