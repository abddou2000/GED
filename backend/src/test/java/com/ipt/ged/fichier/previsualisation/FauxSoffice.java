package com.ipt.ged.fichier.previsualisation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Simulateur de la ligne de commande LibreOffice ({@code soffice}), lancé
 * comme un vrai processus par les tests.
 *
 * <p>Il respecte le contrat observable de {@code soffice --headless
 * --convert-to pdf --outdir <dossier> <fichier>} : code 0 et un fichier
 * {@code <nom>.pdf} dans le dossier de sortie. Le PDF produit embarque les
 * premiers octets du fichier source, pour que le test vérifie que c'est bien
 * le document déchiffré qui a été converti.
 */
public final class FauxSoffice {

    private FauxSoffice() {}

    public static void main(String[] args) throws IOException {
        List<String> a = List.of(args);
        if (a.contains("--version")) {
            System.out.println("LibreOffice 7.6 (simulateur de test)");
            return;
        }
        if (a.contains("--echouer")) {
            System.exit(3);
        }
        Path sortie = Path.of(a.get(a.indexOf("--outdir") + 1));
        Path source = Path.of(a.get(a.size() - 1));
        byte[] debut = Files.readAllBytes(source);
        String extrait = new String(debut, 0, Math.min(40, debut.length), StandardCharsets.ISO_8859_1)
                .replaceAll("[^A-Za-z0-9 ]", "_");
        String nom = source.getFileName().toString();
        String base = nom.contains(".") ? nom.substring(0, nom.lastIndexOf('.')) : nom;
        String pdf = "%PDF-1.4\n% converti par FauxSoffice : " + extrait + "\n%%EOF\n";
        Files.writeString(sortie.resolve(base + ".pdf"), pdf, StandardCharsets.ISO_8859_1);
    }

    /** Commande de lancement du simulateur avec la JVM courante. */
    public static List<String> commande(String... argumentsSupplementaires) {
        String java = ProcessHandle.current().info().command().orElse("java");
        List<String> cmd = new java.util.ArrayList<>(List.of(java, "-cp", System.getProperty("java.class.path"),
                FauxSoffice.class.getName()));
        cmd.addAll(List.of(argumentsSupplementaires));
        return cmd;
    }
}
