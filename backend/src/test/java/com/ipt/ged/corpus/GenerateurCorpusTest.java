package com.ipt.ged.corpus;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class GenerateurCorpusTest {

    @TempDir
    Path dir;

    @Test
    void t028_produitPagesManifesteEtTranscriptions() throws Exception {
        GenerateurCorpus.main(new String[]{"--jeu", "t028", "--pages", "4", "--transcriptions", "2",
                "--fils", "2", "--sortie", dir.toString()});

        Path t = dir.resolve("t028");
        List<String> manifeste = Files.readAllLines(t.resolve("manifeste.csv"));
        assertThat(manifeste).hasSize(5);
        assertThat(manifeste.stream().filter(l -> l.contains(";oui;") && l.split(";")[13].equals("oui"))).hasSize(2);
        try (Stream<Path> pages = Files.list(t.resolve("pages"))) {
            assertThat(pages.count()).isEqualTo(4);
        }
        try (Stream<Path> tr = Files.list(t.resolve("transcriptions"))) {
            List<Path> fichiers = tr.toList();
            assertThat(fichiers).hasSize(2);
            assertThat(Files.readString(fichiers.get(0))).isNotBlank();
        }
        try (Stream<Path> z = Files.list(t.resolve("zones"))) {
            assertThat(Files.readString(z.findFirst().orElseThrow())).contains("\"boite_px\"");
        }
    }

    @Test
    void p14_produitExactementLeNombreDePagesDemande() throws Exception {
        GenerateurCorpus.main(new String[]{"--jeu", "p14", "--pages", "7", "--fils", "2", "--sortie", dir.toString()});

        int total = 0;
        try (Stream<Path> pdfs = Files.walk(dir.resolve("p14/documents"))) {
            for (Path f : pdfs.filter(p -> p.toString().endsWith(".pdf")).toList()) {
                try (PDDocument d = Loader.loadPDF(f.toFile())) {
                    total += d.getNumberOfPages();
                }
            }
        }
        assertThat(total).isEqualTo(7);
    }

    @Test
    void montantsEnToutesLettres() {
        assertThat(Donnees.enLettres(71)).isEqualTo("soixante et onze");
        assertThat(Donnees.enLettres(80)).isEqualTo("quatre-vingts");
        assertThat(Donnees.enLettres(80_000)).isEqualTo("quatre-vingt mille");
        assertThat(Donnees.enLettres(200)).isEqualTo("deux cents");
        assertThat(Donnees.enLettres(200_300)).isEqualTo("deux cent mille trois cents");
        assertThat(Donnees.enLettres(299_496)).isEqualTo("deux cent quatre-vingt-dix-neuf mille quatre cent quatre-vingt-seize");
        assertThat(Donnees.enLettres(1_000_001)).isEqualTo("un million un");
        assertThat(Donnees.montant(1_234_567_89L)).isEqualTo("1 234 567,89");
    }

    @Test
    void repartitionAuPlusFortReste() {
        assertThat(GenerateurCorpus.repartir(300, t -> t.poidsT028).values().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(300);
    }
}
