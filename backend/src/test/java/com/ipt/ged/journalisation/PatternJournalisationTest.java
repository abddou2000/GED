package com.ipt.ged.journalisation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le journal technique respecte l'Article 50 (DAT 7.1) et les règles de
 * rotation et de rétention (DAT 7.3.1).
 *
 * <p>On lit la configuration réellement livrée ({@code logback-spring.xml}) :
 * un test qui recopierait le pattern ne verrait pas une modification du
 * fichier.
 */
class PatternJournalisationTest {

    /** Texte du dossier technique, DAT 7.1, reproduit caractère pour caractère. */
    private static final String PATTERN_ARTICLE_50 =
            "%d{HH:mm:ss.SSS} - [%X{username:-}] [%X{ip:-}] [%thread] - %X{traceId:-}/%X{spanId:-} %-5level - %logger{36} - %msg%n";

    private static Document configuration() throws Exception {
        try (InputStream in = PatternJournalisationTest.class.getResourceAsStream("/logback-spring.xml")) {
            assertThat(in).as("logback-spring.xml présent sur le classpath").isNotNull();
            var fabrique = DocumentBuilderFactory.newInstance();
            fabrique.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return fabrique.newDocumentBuilder().parse(in);
        }
    }

    private static String propriete(Document doc, String balise, String nom, String attribut) {
        NodeList noeuds = doc.getElementsByTagName(balise);
        for (int i = 0; i < noeuds.getLength(); i++) {
            Element e = (Element) noeuds.item(i);
            if (nom.equals(e.getAttribute("name"))) return e.getAttribute(attribut);
        }
        return null;
    }

    private static String texte(Document doc, String balise) {
        NodeList n = doc.getElementsByTagName(balise);
        assertThat(n.getLength()).as(balise).isEqualTo(1);
        return n.item(0).getTextContent().trim();
    }

    @Test
    @DisplayName("Le pattern livré est exactement celui de l'Article 50, pour la console comme pour le fichier")
    void patternIdentique() throws Exception {
        Document doc = configuration();
        assertThat(propriete(doc, "property", "GED_PATTERN", "value")).isEqualTo(PATTERN_ARTICLE_50);

        NodeList patterns = doc.getElementsByTagName("pattern");
        assertThat(patterns.getLength()).isEqualTo(2);
        for (int i = 0; i < patterns.getLength(); i++) {
            assertThat(patterns.item(i).getTextContent().trim()).isEqualTo("${GED_PATTERN}");
        }
    }

    @Test
    @DisplayName("Le pattern produit une ligne au format attendu, MDC renseigné ou vide")
    void formatProduit() {
        LoggerContext contexte = new LoggerContext();
        PatternLayout layout = new PatternLayout();
        layout.setContext(contexte);
        layout.setPattern(PATTERN_ARTICLE_50);
        layout.start();

        LoggingEvent evenement = new LoggingEvent("fqcn",
                contexte.getLogger("com.ipt.ged.document.DocumentService"), Level.INFO,
                "Document déposé", null, null);
        evenement.setThreadName("http-nio-8080-exec-3");
        Map<String, String> mdc = new HashMap<>();
        mdc.put("username", "a.benali");
        mdc.put("ip", "41.250.12.3");
        mdc.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        mdc.put("spanId", "00f067aa0ba902b7");
        evenement.setMDCPropertyMap(mdc);

        String ligne = layout.doLayout(evenement);
        // %logger{36} abrège les premiers segments du paquet pour tenir en 36 caractères.
        assertThat(ligne).matches("\\d{2}:\\d{2}:\\d{2}\\.\\d{3} - \\[a\\.benali] \\[41\\.250\\.12\\.3] "
                + "\\[http-nio-8080-exec-3] - 4bf92f3577b34da6a3ce929d0e0e4736/00f067aa0ba902b7 INFO  - "
                + "c\\.ipt\\.ged\\.document\\.DocumentService - Document déposé\\R");

        // Traitement hors requête : les champs vides restent à leur place.
        LoggingEvent tacheDeFond = new LoggingEvent("fqcn", contexte.getLogger("x"), Level.WARN, "m", null, null);
        tacheDeFond.setThreadName("scheduling-1");
        tacheDeFond.setMDCPropertyMap(Map.of());
        assertThat(layout.doLayout(tacheDeFond))
                .matches("\\d{2}:\\d{2}:\\d{2}\\.\\d{3} - \\[] \\[] \\[scheduling-1] - / WARN  - x - m\\R");
    }

    @Test
    @DisplayName("Rotation quotidienne et à 100 Mo, archives compressées, rétention de 90 jours")
    void rotationEtRetention() throws Exception {
        Document doc = configuration();
        Element politique = (Element) doc.getElementsByTagName("rollingPolicy").item(0);
        assertThat(politique.getAttribute("class"))
                .isEqualTo("ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy");

        String motif = texte(doc, "fileNamePattern");
        assertThat(motif).contains("%d{yyyy-MM-dd}").contains("%i").endsWith(".gz");

        assertThat(texte(doc, "maxFileSize")).isEqualTo("${GED_LOG_TAILLE_MAX}");
        assertThat(propriete(doc, "springProperty", "GED_LOG_TAILLE_MAX", "defaultValue")).isEqualTo("100MB");
        assertThat(texte(doc, "maxHistory")).isEqualTo("${GED_LOG_RETENTION_JOURS}");
        assertThat(propriete(doc, "springProperty", "GED_LOG_RETENTION_JOURS", "defaultValue")).isEqualTo("90");
    }
}
