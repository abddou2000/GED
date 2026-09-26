package com.ipt.ged.fichier.controle;

import com.ipt.ged.fichier.ErreurFichierException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §6.1.5 — client clamd (INSTREAM en TCP) contre un faux démon : fichier sain,
 * EICAR, et échec fermé dans tous les cas d'indisponibilité.
 * ClamAV réel non installé sur le poste : vérifié par simulateur uniquement.
 */
class ClientClamdTest {

    private FauxClamd clamd;

    @BeforeEach
    void demarrer() throws Exception {
        clamd = new FauxClamd();
    }

    @AfterEach
    void arreter() throws Exception {
        clamd.close();
    }

    private ClientClamd client(int bloc, int delaiLectureMs) {
        return new ClientClamd("127.0.0.1", clamd.port(), 1000, delaiLectureMs, bloc);
    }

    @Test
    @DisplayName("Fichier sain : « stream: OK », contenu transmis intégralement en blocs bornés")
    void sain() {
        byte[] contenu = new byte[200_000];
        new Random(7).nextBytes(contenu);
        assertEquals("stream: OK", client(65_536, 5000).analyser(SourceFichier.de(contenu, "a.pdf")));
        assertArrayEquals(contenu, clamd.dernierContenu);
        assertTrue(clamd.taillesBlocs.stream().allMatch(t -> t <= 65_536));
        assertEquals(4, clamd.taillesBlocs.size());
    }

    @Test
    @DisplayName("Chaîne EICAR : 422 FICHIER_INFECTE avec la signature")
    void eicar() {
        byte[] eicar = FauxClamd.eicar().getBytes(StandardCharsets.US_ASCII);
        ErreurFichierException e = assertThrows(ErreurFichierException.class,
                () -> client(8192, 5000).analyser(SourceFichier.de(eicar, "eicar.com")));
        assertEquals(422, e.statut().value());
        assertEquals("FICHIER_INFECTE", e.code());
        assertTrue(e.getMessage().contains("Eicar-Test-Signature"));
    }

    @Test
    @DisplayName("EICAR à cheval sur deux blocs : détecté quand même")
    void eicarAChevalSurDeuxBlocs() {
        String prefixe = "x".repeat(30);
        byte[] contenu = (prefixe + FauxClamd.eicar()).getBytes(StandardCharsets.US_ASCII);
        ErreurFichierException e = assertThrows(ErreurFichierException.class,
                () -> client(40, 5000).analyser(SourceFichier.de(contenu, "a.txt")));
        assertEquals("FICHIER_INFECTE", e.code());
        assertTrue(clamd.taillesBlocs.size() > 1);
    }

    @Test
    @DisplayName("ClamAV arrêté (port fermé) : 503, dépôt refusé")
    void injoignable() throws Exception {
        int portLibre;
        try (ServerSocket s = new ServerSocket(0)) {
            portLibre = s.getLocalPort();
        }
        ClientClamd c = new ClientClamd("127.0.0.1", portLibre, 500, 1000, 8192);
        assertAntivirusIndisponible(() -> c.analyser(SourceFichier.de(new byte[10], "a")));
        assertFalse(c.disponible());
    }

    @Test
    @DisplayName("ClamAV muet au-delà du délai de lecture : 503")
    void muet() {
        clamd.mode(FauxClamd.Mode.MUET);
        assertAntivirusIndisponible(() -> client(8192, 300).analyser(SourceFichier.de(new byte[10], "a")));
    }

    @Test
    @DisplayName("Connexion coupée sans réponse : 503")
    void coupure() {
        clamd.mode(FauxClamd.Mode.FERMETURE_IMMEDIATE);
        assertAntivirusIndisponible(() -> client(8192, 2000).analyser(SourceFichier.de(new byte[100_000], "a")));
    }

    @Test
    @DisplayName("Limite de flux clamd dépassée, ou réponse incompréhensible : 503")
    void reponsesAnormales() {
        clamd.mode(FauxClamd.Mode.LIMITE_DEPASSEE);
        assertAntivirusIndisponible(() -> client(8192, 2000).analyser(SourceFichier.de(new byte[10], "a")));
        clamd.mode(FauxClamd.Mode.REPONSE_INVALIDE);
        assertAntivirusIndisponible(() -> client(8192, 2000).analyser(SourceFichier.de(new byte[10], "a")));
    }

    @Test
    @DisplayName("PING/PONG pour la sonde d'exploitation")
    void ping() {
        assertTrue(client(8192, 2000).disponible());
    }

    private static void assertAntivirusIndisponible(org.junit.jupiter.api.function.Executable e) {
        ErreurFichierException ex = assertThrows(ErreurFichierException.class, e);
        assertEquals(503, ex.statut().value());
        assertEquals("ANTIVIRUS_INDISPONIBLE", ex.code());
    }
}
