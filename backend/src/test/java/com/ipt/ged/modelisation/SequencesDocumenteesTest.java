package com.ipt.ged.modelisation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-05 : les diagrammes de séquence ({@code docs/modelisation/SEQUENCES.md})
 * nomment les classes du code. Une classe renommée ou supprimée sans mise à
 * jour du document ferait mentir la modélisation remise à MMED : la suite
 * échoue alors ici.
 */
class SequencesDocumenteesTest {

    private static final Path DOCUMENT = Path.of("../docs/modelisation/SEQUENCES.md");
    private static final Path SOURCES = Path.of("src/main/java");

    /**
     * Noms qui ne sont pas des classes de l'application : produits externes, classe
     * de Spring, et ce test lui-même, que le document cite comme garde.
     */
    private static final Set<String> HORS_CODE = Set.of("PostgreSQL", "LibreOffice", "FilterRegistrationBean",
            "SequencesDocumenteesTest");

    /** Mot en casse chameau d'au moins deux segments : forme d'un nom de classe. */
    private static final Pattern NOM_DE_CLASSE = Pattern.compile("\\b[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+\\b");

    @Test
    @DisplayName("Chaque classe citée dans les diagrammes de séquence existe dans le code")
    void classesCiteesExistantes() throws IOException {
        Set<String> citees = new TreeSet<>();
        Matcher m = NOM_DE_CLASSE.matcher(Files.readString(DOCUMENT, StandardCharsets.UTF_8));
        while (m.find()) citees.add(m.group());
        citees.removeAll(HORS_CODE);

        Set<String> classes;
        try (Stream<Path> fichiers = Files.walk(SOURCES)) {
            classes = fichiers.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".java"))
                    .map(n -> n.substring(0, n.length() - ".java".length()))
                    .collect(Collectors.toSet());
        }

        List<String> absentes = citees.stream().filter(c -> !classes.contains(c)).toList();
        assertThat(citees).as("le document doit citer les classes des flux").hasSizeGreaterThan(20);
        assertThat(absentes).as("classes citées dans SEQUENCES.md et absentes du code").isEmpty();
    }
}
