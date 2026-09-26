package com.ipt.ged.fichier.previsualisation;

import com.ipt.ged.fichier.DepotClesFichierMemoire;
import com.ipt.ged.fichier.ErreurFichierException;
import com.ipt.ged.fichier.StockageChiffre;
import com.ipt.ged.fichier.cles.KeystoreKeyProvider;
import com.ipt.ged.fichier.controle.Echantillons;
import com.ipt.ged.fichier.controle.FormatsReconnus;
import com.ipt.ged.fichier.stockage.FileStoreDisque;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §6.1.6 — prévisualisation : PDF et images déchiffrés en flux sans copie en
 * clair ; bureautique convertie (LibreOffice simulé par un vrai processus)
 * puis mise en cache chiffré.
 */
class ServicePrevisualisationTest {

    @TempDir Path dossier;

    private KeystoreKeyProvider keyProvider;
    private DepotClesFichierMemoire cles;
    private StockageChiffre documents;
    private FileStoreDisque storeCache;
    private StockageChiffre cache;
    private Path travail;

    @BeforeEach
    void preparer() {
        keyProvider = new KeystoreKeyProvider(dossier.resolve("kek.p12"), "mdp".toCharArray(), true);
        cles = new DepotClesFichierMemoire();
        documents = new StockageChiffre(new FileStoreDisque(dossier.resolve("coffre")), keyProvider, cles);
        storeCache = new FileStoreDisque(dossier.resolve("cache"));
        cache = new StockageChiffre(storeCache, keyProvider, cles);
        travail = dossier.resolve("travail");
    }

    private ServicePrevisualisation service(ConvertisseurBureautique convertisseur) {
        return new ServicePrevisualisation(documents, cache, convertisseur, travail, 200L * 1024 * 1024);
    }

    private UUID deposer(byte[] contenu) {
        return documents.ecrire(new ByteArrayInputStream(contenu), 10_000_000).id();
    }

    private static byte[] lire(ServicePrevisualisation.Apercu a) throws IOException {
        try (InputStream in = a.flux()) {
            return in.readAllBytes();
        }
    }

    private List<Path> fichiersEnClair() throws IOException {
        try (Stream<Path> s = Files.walk(dossier)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().endsWith(".enc"))
                    .filter(p -> !p.getFileName().toString().endsWith(".p12"))
                    .toList();
        }
    }

    @Test
    @DisplayName("PDF servi déchiffré en flux, taille exacte, sans aucune copie en clair")
    void pdfEnFlux() throws Exception {
        byte[] pdf = Echantillons.pdf();
        UUID id = deposer(pdf);
        ServicePrevisualisation.Apercu a = service(new ConvertisseurLibreOffice(List.of("absent-xyz"), Duration.ofSeconds(5)))
                .ouvrir(id, FormatsReconnus.PDF);
        assertEquals("application/pdf", a.typeMime());
        assertEquals(pdf.length, a.taille());
        assertArrayEquals(pdf, lire(a));
        assertTrue(fichiersEnClair().isEmpty(), "aucun fichier en clair : " + fichiersEnClair());
    }

    @Test
    @DisplayName("Image servie telle quelle ; texte servi en UTF-8 explicite")
    void imageEtTexte() throws Exception {
        byte[] png = Echantillons.image("png");
        ServicePrevisualisation s = service(new ConvertisseurLibreOffice(List.of("absent-xyz"), Duration.ofSeconds(5)));
        ServicePrevisualisation.Apercu a = s.ouvrir(deposer(png), "image/png");
        assertEquals("image/png", a.typeMime());
        assertArrayEquals(png, lire(a));
        ServicePrevisualisation.Apercu t = s.ouvrir(deposer("é".getBytes(StandardCharsets.UTF_8)), "text/plain");
        assertEquals("text/plain;charset=UTF-8", t.typeMime());
    }

    @Test
    @DisplayName("Bureautique : converti une fois par LibreOffice, mis en cache chiffré, répertoire de travail nettoyé")
    void bureautiqueEnCacheChiffre() throws Exception {
        byte[] docx = Echantillons.docx();
        UUID id = deposer(docx);
        AtomicInteger conversions = new AtomicInteger();
        ConvertisseurLibreOffice lo = new ConvertisseurLibreOffice(FauxSoffice.commande(), Duration.ofSeconds(60));
        ConvertisseurBureautique compteur = new ConvertisseurBureautique() {
            @Override public boolean disponible() { return lo.disponible(); }
            @Override public Path convertirEnPdf(Path source, Path sortie) {
                conversions.incrementAndGet();
                return lo.convertirEnPdf(source, sortie);
            }
        };
        ServicePrevisualisation s = service(compteur);

        ServicePrevisualisation.Apercu a = s.ouvrir(id, FormatsReconnus.DOCX);
        assertEquals("application/pdf", a.typeMime());
        String pdf = new String(lire(a), StandardCharsets.ISO_8859_1);
        assertTrue(pdf.startsWith("%PDF-1.4"));
        assertTrue(pdf.contains("converti par FauxSoffice : PK"), "c'est bien le docx déchiffré qui a été converti");

        UUID idCache = ServicePrevisualisation.idCache(id);
        assertTrue(storeCache.existe(idCache));
        String brutCache = new String(Files.readAllBytes(storeCache.chemin(idCache)), StandardCharsets.ISO_8859_1);
        assertFalse(brutCache.contains("%PDF"), "le cache est chiffré");
        assertTrue(fichiersEnClair().isEmpty(), "répertoire de travail nettoyé : " + fichiersEnClair());

        // Second affichage : servi depuis le cache, sans nouvelle conversion.
        assertEquals(pdf, new String(lire(s.ouvrir(id, FormatsReconnus.DOCX)), StandardCharsets.ISO_8859_1));
        assertEquals(1, conversions.get());

        // Purge du document : la copie en cache disparaît avec sa clé.
        s.invaliderCache(id);
        assertFalse(storeCache.existe(idCache));
        assertTrue(cles.trouver(idCache).isEmpty());
    }

    @Test
    @DisplayName("LibreOffice absent : bureautique désactivée proprement (503), PDF toujours servi")
    void libreOfficeAbsent() throws Exception {
        ConvertisseurLibreOffice absent = new ConvertisseurLibreOffice(List.of("soffice-inexistant-e5"), Duration.ofSeconds(5));
        assertFalse(absent.disponible());
        ServicePrevisualisation s = service(absent);
        ErreurFichierException e = assertThrows(ErreurFichierException.class,
                () -> s.ouvrir(deposer(Echantillons.docx()), FormatsReconnus.DOCX));
        assertEquals(503, e.statut().value());
        assertEquals("CONVERSION_INDISPONIBLE", e.code());
        byte[] pdf = Echantillons.pdf();
        assertArrayEquals(pdf, lire(s.ouvrir(deposer(pdf), FormatsReconnus.PDF)));
        assertTrue(fichiersEnClair().isEmpty());
    }

    @Test
    @DisplayName("Conversion en échec : 503, rien en cache, répertoire de travail nettoyé")
    void conversionEnEchec() throws Exception {
        ServicePrevisualisation s = service(new ConvertisseurLibreOffice(FauxSoffice.commande("--echouer"), Duration.ofSeconds(60)));
        UUID id = deposer(Echantillons.docx());
        ErreurFichierException e = assertThrows(ErreurFichierException.class, () -> s.ouvrir(id, FormatsReconnus.DOCX));
        assertEquals("CONVERSION_INDISPONIBLE", e.code());
        assertFalse(storeCache.existe(ServicePrevisualisation.idCache(id)));
        assertTrue(fichiersEnClair().isEmpty());
    }

    @Test
    @DisplayName("Format sans aperçu (archive) : 415 APERCU_NON_DISPONIBLE")
    void formatSansApercu() {
        ServicePrevisualisation s = service(new ConvertisseurLibreOffice(List.of("absent-xyz"), Duration.ofSeconds(5)));
        ErreurFichierException e = assertThrows(ErreurFichierException.class,
                () -> s.ouvrir(deposer(new byte[]{1}), "application/zip"));
        assertEquals(415, e.statut().value());
        assertEquals("APERCU_NON_DISPONIBLE", e.code());
    }
}
