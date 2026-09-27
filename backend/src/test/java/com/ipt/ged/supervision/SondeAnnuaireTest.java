package com.ipt.ged.supervision;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sonde de l'annuaire avec un, deux ou aucun contrôleur joignable (DAT 6.7,
 * décision D4 : un seul contrôleur aujourd'hui, un second prévu).
 *
 * <p>Aucun annuaire sur le poste : {@link AnnuaireSimule} répond au strict
 * nécessaire du protocole LDAP v3 (encodage BER) — liaison anonyme, lecture du
 * RootDSE — ce qu'un contrôleur AD accepte sans compte. Le client est le vrai
 * client JNDI du JDK, celui qu'utilise la sonde en production.
 */
class SondeAnnuaireTest {

    private final List<AnnuaireSimule> annuaires = new ArrayList<>();

    @AfterEach
    void arreter() throws IOException {
        for (AnnuaireSimule a : annuaires) a.close();
    }

    private String annuaireDisponible() throws IOException {
        AnnuaireSimule a = new AnnuaireSimule();
        annuaires.add(a);
        return "ldap://127.0.0.1:" + a.port();
    }

    private static String controleurInjoignable() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return "ldap://127.0.0.1:" + s.getLocalPort();
        }
    }

    @Test
    @DisplayName("Un seul contrôleur, joignable : UP")
    void unSeulControleurDisponible() throws Exception {
        String url = annuaireDisponible();
        SondeAnnuaire sonde = new SondeAnnuaire(true, List.of(url), 2000);

        Health h = sonde.health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("disponibles", "1/1").doesNotContainKey("anomalie");
        assertThat(sonde.etatControleur(url)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Un seul contrôleur, injoignable : DOWN (plus personne ne peut se connecter)")
    void unSeulControleurIndisponible() throws Exception {
        String url = controleurInjoignable();
        SondeAnnuaire sonde = new SondeAnnuaire(true, List.of(url), 1000);

        assertThat(sonde.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(sonde.etatControleur(url)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Deux contrôleurs dont un tombé : UP dégradé, le contrôleur tombé est désigné")
    void deuxControleursDontUnTombe() throws Exception {
        String disponible = annuaireDisponible();
        String tombe = controleurInjoignable();
        SondeAnnuaire sonde = new SondeAnnuaire(true, List.of(disponible, tombe), 1000);

        Health h = sonde.health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("disponibles", "1/2").containsKey("anomalie");
        @SuppressWarnings("unchecked")
        Map<String, String> controleurs = (Map<String, String>) h.getDetails().get("controleurs");
        assertThat(controleurs.get(disponible)).isEqualTo("UP");
        assertThat(controleurs.get(tombe)).startsWith("DOWN");
        assertThat(sonde.etatControleur(tombe)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Deux contrôleurs joignables : UP sans anomalie ; aucun joignable : DOWN")
    void deuxControleurs() throws Exception {
        SondeAnnuaire tousLa = new SondeAnnuaire(true, List.of(annuaireDisponible(), annuaireDisponible()), 2000);
        assertThat(tousLa.health().getDetails()).containsEntry("disponibles", "2/2").doesNotContainKey("anomalie");

        SondeAnnuaire aucun = new SondeAnnuaire(true, List.of(controleurInjoignable(), controleurInjoignable()), 1000);
        assertThat(aucun.health().getStatus()).isEqualTo(Status.DOWN);
    }

    /**
     * Annuaire LDAP v3 minimal : répond succès à toute liaison, et à toute
     * recherche par une entrée RootDSE portant {@code namingContexts}.
     */
    static final class AnnuaireSimule implements AutoCloseable {

        private final ServerSocket serveur = new ServerSocket(0);

        AnnuaireSimule() throws IOException {
            Thread t = new Thread(this::servir, "annuaire-simule");
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return serveur.getLocalPort();
        }

        private void servir() {
            while (!serveur.isClosed()) {
                try (Socket client = serveur.accept()) {
                    dialoguer(client.getInputStream(), client.getOutputStream());
                } catch (IOException fin) {
                    // client parti ou serveur fermé
                }
            }
        }

        private static void dialoguer(InputStream in, OutputStream out) throws IOException {
            DataInputStream din = new DataInputStream(in);
            while (true) {
                int tag = din.read();
                if (tag != 0x30) return;                           // fin de flux
                byte[] message = new byte[lireLongueur(din)];
                din.readFully(message);
                // LDAPMessage ::= SEQUENCE { messageID INTEGER, protocolOp CHOICE }
                int longueurId = message[1];
                byte[] id = java.util.Arrays.copyOfRange(message, 2, 2 + longueurId);
                int operation = message[2 + longueurId] & 0xff;
                switch (operation) {
                    case 0x60 -> out.write(enveloppe(id, tlv(0x61, resultatSucces())));        // Bind
                    case 0x63 -> {                                                              // Search
                        byte[] valeur = tlv(0x04, "DC=marchica,DC=local".getBytes(StandardCharsets.UTF_8));
                        byte[] attribut = tlv(0x30, concat(
                                tlv(0x04, "namingContexts".getBytes(StandardCharsets.US_ASCII)),
                                tlv(0x31, valeur)));
                        byte[] entree = tlv(0x64, concat(tlv(0x04, new byte[0]), tlv(0x30, attribut)));
                        out.write(enveloppe(id, entree));
                        out.write(enveloppe(id, tlv(0x65, resultatSucces())));
                    }
                    case 0x42 -> { return; }                                                    // Unbind
                    default -> { /* abandon, etc. : ignoré */ }
                }
                out.flush();
            }
        }

        private static int lireLongueur(DataInputStream din) throws IOException {
            int premier = din.read();
            if (premier < 0x80) return premier;
            int n = premier & 0x7f, longueur = 0;
            for (int i = 0; i < n; i++) longueur = (longueur << 8) | din.read();
            return longueur;
        }

        /** LDAPResult { resultCode success, matchedDN "", diagnosticMessage "" }. */
        private static byte[] resultatSucces() {
            return concat(new byte[]{0x0a, 0x01, 0x00}, tlv(0x04, new byte[0]), tlv(0x04, new byte[0]));
        }

        private static byte[] enveloppe(byte[] id, byte[] operation) {
            return tlv(0x30, concat(tlv(0x02, id), operation));
        }

        private static byte[] tlv(int tag, byte[] valeur) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(tag);
            if (valeur.length < 0x80) {
                b.write(valeur.length);
            } else {
                b.write(0x82);
                b.write(valeur.length >> 8);
                b.write(valeur.length & 0xff);
            }
            b.writeBytes(valeur);
            return b.toByteArray();
        }

        private static byte[] concat(byte[]... parties) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            for (byte[] p : parties) b.writeBytes(p);
            return b.toByteArray();
        }

        @Override
        public void close() throws IOException {
            serveur.close();
        }
    }
}
