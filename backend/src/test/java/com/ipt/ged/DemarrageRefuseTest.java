package com.ipt.ged;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O1 (recette vague 9) : une GED dont le démarrage est refusé (configuration
 * invalide) doit s'arrêter, avec un code non nul, pour que systemd le voie et
 * la relance. En profil dev, la JVM restait vivante (fil d'écoute de l'annuaire
 * embarqué). Le test lance la vraie application ({@link GedApplication#main})
 * dans une JVM fille, profil dev, avec un cache D15 de 6 min (refusé : 5 min au
 * plus) et attend sa fin.
 */
class DemarrageRefuseTest {

    /** Démarrage jusqu'au refus : une vingtaine de secondes sur le poste ; large marge. */
    private static final long DELAI_MAX_SECONDES = 240;

    @Test
    @DisplayName("Démarrage refusé en profil dev : la JVM s'arrête avec un code non nul")
    void demarrageRefuseArreteLaJvm(@TempDir Path dossier) throws Exception {
        Path journal = dossier.resolve("demarrage.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-Xmx512m", "-cp", classpathApplication(),
                GedApplication.class.getName())
                .directory(dossier.toFile())
                .redirectErrorStream(true)
                .redirectOutput(journal.toFile());
        Map<String, String> env = pb.environment();
        int portAnnuaire = portLibre();
        env.put("SPRING_PROFILES_ACTIVE", "dev");
        // Base de test de ce poste (comme application-test.yml), jamais la base de développement.
        env.put("DB_NAME", baseDeTest());
        env.remove("SPRING_DATASOURCE_URL");
        env.put("GED_IDENTITE_ANNUAIRE_EMBARQUE_PORT", Integer.toString(portAnnuaire));
        env.put("GED_IDENTITE_ANNUAIRE_URLS", "ldap://localhost:" + portAnnuaire);
        env.put("GED_LDAP_URLS", "ldap://localhost:" + portAnnuaire);
        env.put("SERVER_PORT", "0");
        env.put("GED_MANAGEMENT_PORT", Integer.toString(portLibre()));
        env.put("GED_KEYSTORE_CHEMIN", dossier.resolve("cles/ged-kek.p12").toString());
        env.put("GED_STOCKAGE_RACINE", dossier.resolve("coffre").toString());
        env.put("GED_CACHE_APERCU_RACINE", dossier.resolve("cache-apercu").toString());
        // La configuration invalide : refusée par EtatCompteEnCache (D15).
        env.put("GED_DELEGATION_CACHE_ETAT_COMPTE", "6m");

        Process jvm = pb.start();
        boolean finie = jvm.waitFor(DELAI_MAX_SECONDES, TimeUnit.SECONDS);
        String sortie = "";
        try {
            sortie = Files.readString(journal, StandardCharsets.UTF_8);
            if (!finie) {
                jvm.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
            assertThat(sortie).as("le démarrage doit être refusé pour la raison attendue")
                    .contains("hors de [0, 5 min]");
            assertThat(finie).as("JVM toujours vivante %d s après le démarrage refusé", DELAI_MAX_SECONDES).isTrue();
            assertThat(jvm.exitValue()).as("code de sortie d'un démarrage refusé").isNotZero();
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + "\n--- fin du journal de la JVM fille ---\n"
                    + fin(sortie, 40), e);
        } finally {
            if (jvm.isAlive()) jvm.destroyForcibly();
        }
    }

    /**
     * Classpath de la suite sans les classes de test (leurs configurations
     * seraient analysées par l'application), en chemins absolus : la JVM fille
     * tourne dans un dossier temporaire.
     */
    private static String classpathApplication() {
        return java.util.Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .filter(e -> !e.isBlank())
                .map(e -> Path.of(e).toAbsolutePath().normalize())
                .filter(p -> !p.endsWith("test-classes"))
                .map(Path::toString)
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }

    private static String baseDeTest() {
        String test = System.getenv("DB_NAME_TEST");
        if (test != null && !test.isBlank()) return test;
        String dev = System.getenv("DB_NAME");
        return (dev == null || dev.isBlank() ? "ged_dev1" : dev) + "_test";
    }

    private static int portLibre() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    private static String fin(String texte, int lignes) {
        List<String> l = texte.lines().toList();
        return String.join("\n", l.subList(Math.max(0, l.size() - lignes), l.size()));
    }
}
