package com.ipt.ged.autorisation;

import com.ipt.ged.document.UploadDocumentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test d'architecture du point d'application unique (T-072, §12.2.3 :
 * « aucun module ne réimplémente la règle »).
 *
 * <p>Toute lecture de documents EN MASSE (liste, total, agrégat) doit passer
 * par la spécification de droits d'{@link AccessPredicate}. Ce test échoue si
 * quelqu'un :
 * <ul>
 *   <li>ajoute au dépôt des documents une méthode de lecture en masse (le
 *       dépôt n'expose plus que des lectures par identifiant, verrouillées ou
 *       par lot d'identifiants déjà filtrés) ;</li>
 *   <li>appelle {@code findAll()}, {@code findAll(Sort)},
 *       {@code findAll(Pageable)} ou {@code count()} sans spécification sur
 *       ce dépôt ;</li>
 *   <li>injecte le dépôt des documents ou des rattachements dans un
 *       contrôleur, contournant les services qui appliquent les droits.</li>
 * </ul>
 */
class ArchitectureDroitsTest {

    private static final Path SOURCES = Path.of("src/main/java");

    /** Méthodes propres au dépôt des documents, toutes non « en masse ». */
    private static final Set<String> METHODES_PERMISES = Set.of(
            "findByIdPourEcriture", "findByIdInAndSupprimeFalse", "findByIdInAndSupprimeTrue",
            // Jeu de démonstration du profil dev (IndexationSeeder), vérifié ci-dessous.
            "findBySupprimeFalseOrderByIdDesc");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> s = Files.walk(SOURCES)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String lire(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("Le dépôt des documents n'expose aucune lecture en masse non filtrée")
    void depotSansLectureEnMasse() {
        Set<String> declarees = new TreeSet<>();
        for (Method m : UploadDocumentRepository.class.getDeclaredMethods()) {
            if (!m.isSynthetic() && !m.isDefault()) declarees.add(m.getName());
        }
        declarees.removeAll(METHODES_PERMISES);
        assertEquals(Set.of(), declarees,
                "Lecture de documents hors AccessPredicate : passer par findAll(Specification) avec droits.documents(...)");
    }

    @Test
    @DisplayName("Aucun findAll()/count() sans spécification sur le dépôt des documents")
    void aucunAppelNonFiltre() throws IOException {
        List<String> fautes = new ArrayList<>();
        Pattern champ = Pattern.compile("UploadDocumentRepository\\s+(\\w+)\\s*[;,)=]");
        for (Path p : sources()) {
            String code = lire(p);
            if (!code.contains("UploadDocumentRepository") || p.endsWith("UploadDocumentRepository.java")) continue;
            Matcher m = champ.matcher(code);
            while (m.find()) {
                String nom = m.group(1);
                Pattern appel = Pattern.compile("\\b" + nom
                        + "\\s*\\.\\s*(findAll\\s*\\(\\s*(\\)|Sort|Pageable|PageRequest)|count\\s*\\(\\s*\\))");
                if (appel.matcher(code).find()) fautes.add(p + " : " + nom);
            }
            if (code.contains("findBySupprimeFalseOrderByIdDesc") && !p.endsWith("IndexationSeeder.java")) {
                fautes.add(p + " : findBySupprimeFalseOrderByIdDesc hors jeu de démonstration");
            }
        }
        assertEquals(List.of(), fautes);
    }

    @Test
    @DisplayName("Aucun contrôleur n'accède directement aux documents ou aux rattachements")
    void controleursSansDepotDocuments() throws IOException {
        List<String> fautes = new ArrayList<>();
        for (Path p : sources()) {
            String code = lire(p);
            if (!code.contains("@RestController")) continue;
            for (String interdit : Arrays.asList("UploadDocumentRepository", "DocumentRattachementRepository",
                    "DocumentConfidentielDesigneRepository", "HabilitationRepository")) {
                if (code.contains(interdit)) fautes.add(p.getFileName() + " -> " + interdit);
            }
        }
        assertEquals(List.of(), fautes);
    }
}
