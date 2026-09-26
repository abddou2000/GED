package com.ipt.ged.fichier.reprise;

import com.ipt.ged.fichier.DepotClesFichierMemoire;
import com.ipt.ged.fichier.StockageChiffre;
import com.ipt.ged.fichier.cles.KeystoreKeyProvider;
import com.ipt.ged.fichier.Refus;
import com.ipt.ged.fichier.controle.AnalyseurAntivirus;
import com.ipt.ged.fichier.controle.DetecteurTypeReel;
import com.ipt.ged.fichier.controle.Echantillons;
import com.ipt.ged.fichier.controle.SourceFichier;
import com.ipt.ged.fichier.stockage.FileStoreDisque;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reprise des fichiers en clair de l'ancien stockage ({@code <espace>/<uuid>.<ext>})
 * vers le stockage chiffré, sur un dossier d'exemple.
 */
class RepriseFichiersEnClairTest {

    @TempDir Path dossier;

    private Path ancien;
    private Path rapport;
    private FileStoreDisque store;
    private DepotClesFichierMemoire cles;
    private StockageChiffre stockage;
    private final Map<String, byte[]> originaux = new java.util.LinkedHashMap<>();

    @BeforeEach
    void preparer() throws Exception {
        ancien = dossier.resolve("storage/ged");
        rapport = dossier.resolve("reprise.csv");
        store = new FileStoreDisque(dossier.resolve("coffre"));
        cles = new DepotClesFichierMemoire();
        stockage = new StockageChiffre(store,
                new KeystoreKeyProvider(dossier.resolve("kek.p12"), "mdp".toCharArray(), true), cles);
        originaux.put("1/" + UUID.randomUUID() + ".pdf", Echantillons.pdf());
        originaux.put("1/" + UUID.randomUUID() + ".docx", Echantillons.docx());
        originaux.put("2/" + UUID.randomUUID() + ".png", Echantillons.image("png"));
        originaux.put("2/" + UUID.randomUUID() + ".txt", "Note de service".getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, byte[]> e : originaux.entrySet()) {
            Path p = ancien.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.write(p, e.getValue());
        }
    }

    private RepriseFichiersEnClair outil() {
        return new RepriseFichiersEnClair(stockage, new DetecteurTypeReel(), null, 200L * 1024 * 1024);
    }

    private Map<String, String[]> lignesRapport() throws Exception {
        List<String> lignes = Files.readAllLines(rapport, StandardCharsets.UTF_8);
        assertEquals(RepriseFichiersEnClair.ENTETE, lignes.get(0));
        return lignes.stream().skip(1).map(l -> l.split(";", -1))
                .collect(Collectors.toMap(c -> c[0], Function.identity()));
    }

    private static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    @Test
    @DisplayName("Chaque fichier est chiffré, son empreinte calculée et relue ; les originaux restent intacts")
    void reprise() throws Exception {
        RepriseFichiersEnClair.Rapport r = outil().reprendre(ancien, rapport);
        assertEquals(4, r.reprises());
        assertEquals(0, r.echecs());

        Map<String, String[]> lignes = lignesRapport();
        assertEquals(originaux.keySet(), lignes.keySet());
        for (Map.Entry<String, byte[]> e : originaux.entrySet()) {
            String[] l = lignes.get(e.getKey());
            assertEquals("OK", l[6]);
            UUID id = UUID.fromString(l[1]);
            assertEquals(sha256(e.getValue()), l[2]);
            assertEquals(String.valueOf(e.getValue().length), l[3]);
            assertTrue(store.existe(id));
            try (InputStream in = stockage.lire(id)) {
                assertArrayEquals(e.getValue(), in.readAllBytes());
            }
            // Aucun original supprimé : la suppression est une étape séparée.
            assertArrayEquals(e.getValue(), Files.readAllBytes(ancien.resolve(e.getKey())));
        }
        assertEquals("application/pdf", lignes.values().stream().filter(l -> l[0].endsWith(".pdf")).findFirst().orElseThrow()[4]);
    }

    @Test
    @DisplayName("Relancée, la reprise ignore les fichiers déjà repris (idempotente)")
    void idempotente() throws Exception {
        outil().reprendre(ancien, rapport);
        Path nouveau = ancien.resolve("3/" + UUID.randomUUID() + ".pdf");
        Files.createDirectories(nouveau.getParent());
        Files.write(nouveau, Echantillons.pdf());

        RepriseFichiersEnClair.Rapport r = outil().reprendre(ancien, rapport);
        assertEquals(1, r.reprises());
        assertEquals(4, r.dejaReprises());
        assertEquals(5, cles.taille());
        assertEquals(6, Files.readAllLines(rapport).size(), "en-tête + 5 lignes, sans doublon");
    }

    @Test
    @DisplayName("Avec antivirus : un fichier infecté n'est pas repris et est signalé, la reprise continue")
    void antivirus() throws Exception {
        Path infecte = ancien.resolve("2/" + UUID.randomUUID() + "-infecte.txt");
        Files.writeString(infecte, "contenu signalé par l'analyseur de test");
        // Le client clamd et la chaîne EICAR sont testés à part (ClientClamdTest) ;
        // ici seul compte le traitement d'un refus par l'outil de reprise.
        AnalyseurAntivirus analyseur = new AnalyseurAntivirus() {
            @Override public String analyser(SourceFichier s) {
                if (s.nomOrigine().endsWith("-infecte.txt")) throw Refus.infecte("Eicar-Test-Signature");
                return "stream: OK";
            }
            @Override public boolean disponible() { return true; }
        };
        RepriseFichiersEnClair.Rapport r = new RepriseFichiersEnClair(stockage, new DetecteurTypeReel(), analyseur,
                200L * 1024 * 1024).reprendre(ancien, rapport);
        assertEquals(4, r.reprises());
        assertEquals(1, r.echecs());
        String relatif = ancien.relativize(infecte).toString().replace('\\', '/');
        assertEquals("FICHIER_INFECTE", lignesRapport().get(relatif)[6]);
        assertEquals(4, cles.taille());
    }

    @Test
    @DisplayName("Fichier au-delà du plafond : signalé au rapport, non repris, la reprise continue")
    void tropGros() throws Exception {
        RepriseFichiersEnClair petitPlafond = new RepriseFichiersEnClair(stockage, new DetecteurTypeReel(), null, 20);
        RepriseFichiersEnClair.Rapport r = petitPlafond.reprendre(ancien, rapport);
        assertEquals(1, r.reprises(), "seule la note de 15 octets passe");
        assertEquals(3, r.echecs());
        assertTrue(lignesRapport().values().stream().filter(l -> !l[6].equals("OK"))
                .allMatch(l -> l[6].equals("FICHIER_TROP_VOLUMINEUX")));
    }
}
