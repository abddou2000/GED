package com.ipt.ged.ocr.file;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ipt.ged.ocr.moteur.ModelesEntiers;
import com.ipt.ged.ocr.moteur.MoteurTesseract;
import com.ipt.ged.ocr.moteur.ReglageOcr;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P-14 / R30 — réglage du débit OCR retenu après mesure
 * ({@code docs/exploitation/ESSAIS-DE-CHARGE.md} § 2.4) : modèles compactés en
 * entiers et rendu à 200 dpi par défaut, chacun réglable ({@code ged.ocr.modeles},
 * {@code ged.ocr.chaine.dpi}).
 */
class ReglageDebitOcrTest {

    private static final String TESSERACT = System.getenv().getOrDefault("GED_TESSERACT",
            "C:/Program Files/Tesseract-OCR/tesseract.exe");
    private static final String LIVRES = Path.of("tessdata").toAbsolutePath().toString();

    /** application.yml tel que livré, placeholders résolus sans variable d'environnement GED_OCR_*. */
    private static StandardEnvironment configurationLivree() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        for (PropertySource<?> s : new YamlPropertySourceLoader().load("application",
                new ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(s);
        }
        return env;
    }

    @Test
    @DisplayName("Défauts livrés : modèles compactés en entiers, rendu à 200 dpi, psm 3, un fil par processus")
    void defautsLivres() throws Exception {
        StandardEnvironment env = configurationLivree();
        assertEquals("entiers", env.getProperty("ged.ocr.modeles"));
        assertEquals("3", env.getProperty("ged.ocr.psm"));
        ProprietesChaineOcr p = Binder.get(env).bind("ged.ocr.chaine", ProprietesChaineOcr.class)
                .orElseThrow(IllegalStateException::new);
        assertEquals(200, p.getDpi());
        assertEquals("ara+fra", p.getLangueDefaut());
        assertEquals(200, new ProprietesChaineOcr().getDpi(), "défaut sans configuration");
    }

    @Test
    @DisplayName("ged.ocr.modeles=precis : modèles livrés passés tels quels à Tesseract")
    void precis(@TempDir Path dir) {
        MoteurTesseract m = ConfigurationChaineOcr.moteur(TESSERACT, LIVRES, "1", "3", "precis",
                dir.toString(), "");
        assertEquals(LIVRES, m.tessdata());
    }

    @Test
    @DisplayName("ged.ocr.modeles inconnu : refus au démarrage")
    void inconnu(@TempDir Path dir) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ConfigurationChaineOcr
                .moteur(TESSERACT, LIVRES, "1", "3", "rapides", dir.toString(), ""));
        assertTrue(e.getMessage().contains("ged.ocr.modeles"), e.getMessage());
    }

    @Test
    @DisplayName("ged.ocr.modeles=entiers sans combine_tessdata : repli sur les modèles livrés")
    void entiersSansOutil(@TempDir Path dir) {
        MoteurTesseract m = ConfigurationChaineOcr.moteur(TESSERACT, LIVRES, "1", "3", "entiers",
                dir.resolve("entiers").toString(), "combine-inexistant-p14");
        assertEquals(LIVRES, m.tessdata());
        // Variable exportée vide : le défaut (entiers), pas un refus de démarrer.
        assertEquals(LIVRES, ConfigurationChaineOcr.moteur(TESSERACT, LIVRES, "1", "3", " ",
                dir.resolve("entiers").toString(), "combine-inexistant-p14").tessdata());
    }

    @Test
    @DisplayName("Mode de modèles réellement employé : precis, repli (sans combine_tessdata), installation")
    void modeEmploye(@TempDir Path dir) {
        String rep = dir.resolve("entiers").toString();
        ReglageOcr r = ConfigurationChaineOcr.reglage(TESSERACT, LIVRES, "precis", rep, "", 200);
        assertEquals(new ReglageOcr("precis", LIVRES, 200), r);
        r = ConfigurationChaineOcr.reglage(TESSERACT, LIVRES, "entiers", rep, "combine-inexistant-p14", 300);
        assertEquals(new ReglageOcr("repli", LIVRES, 300), r);
        r = ConfigurationChaineOcr.reglage(TESSERACT, "", "entiers", rep, "", 200);
        assertEquals(new ReglageOcr("installation", "", 200), r);
    }

    @Test
    @DisplayName("Démarrage : modèles employés et résolution du rendu journalisés")
    void journalDuReglage(@TempDir Path dir) {
        Logger journal = (Logger) LoggerFactory.getLogger(ConfigurationChaineOcr.class);
        ListAppender<ILoggingEvent> lignes = new ListAppender<>();
        lignes.start();
        journal.addAppender(lignes);
        Level avant = journal.getLevel();
        journal.setLevel(Level.INFO);
        try {
            ProprietesChaineOcr p = new ProprietesChaineOcr();
            p.setDpi(300);
            ReglageOcr r = new ConfigurationChaineOcr().reglageOcr(TESSERACT, LIVRES, "entiers",
                    dir.resolve("entiers").toString(), "combine-inexistant-p14", p);
            assertEquals("repli", r.modeles());
            assertEquals(List.of("Réglage OCR : modèles repli (" + LIVRES + "), pages PDF rendues à 300 dpi"),
                    lignes.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        } finally {
            journal.detachAppender(lignes);
            journal.setLevel(avant);
        }
    }

    @Test
    @DisplayName("ged.ocr.modeles=entiers : Tesseract lit la copie compactée, fra et ara présents")
    void entiers(@TempDir Path dir) {
        assumeTrue(Files.isRegularFile(Path.of(TESSERACT))
                && Files.isRegularFile(Path.of(ModelesEntiers.combineParDefaut(TESSERACT))), "Tesseract absent");
        Path cible = dir.resolve("entiers");
        MoteurTesseract m = ConfigurationChaineOcr.moteur(TESSERACT, LIVRES, "1", "3", "entiers",
                cible.toString(), "");
        assertEquals(cible.toString(), m.tessdata());
        assertTrue(m.languesInstallees().containsAll(java.util.List.of("fra", "ara")), m.languesInstallees().toString());
    }
}
