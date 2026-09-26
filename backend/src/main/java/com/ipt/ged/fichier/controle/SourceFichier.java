package com.ipt.ged.fichier.controle;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fichier reçu, relisible : la chaîne de contrôle le lit plusieurs fois
 * (signature, antivirus, chiffrement) sans jamais le garder entier en mémoire.
 */
public interface SourceFichier {

    /** Ouvre un nouveau flux depuis le début. L'appelant le ferme. */
    InputStream ouvrir() throws IOException;

    /** Taille en octets, ou {@code -1} si inconnue (vérifiée alors pendant le flux). */
    long taille();

    /** Nom d'origine : métadonnée d'affichage, jamais utilisée pour décider du type. */
    String nomOrigine();

    static SourceFichier de(MultipartFile fichier) {
        return new SourceFichier() {
            @Override public InputStream ouvrir() throws IOException { return fichier.getInputStream(); }
            @Override public long taille() { return fichier.getSize(); }
            @Override public String nomOrigine() { return fichier.getOriginalFilename(); }
        };
    }

    static SourceFichier de(Path chemin) {
        return new SourceFichier() {
            @Override public InputStream ouvrir() throws IOException { return Files.newInputStream(chemin); }
            @Override public long taille() {
                try {
                    return Files.size(chemin);
                } catch (IOException e) {
                    return -1;
                }
            }
            @Override public String nomOrigine() { return chemin.getFileName().toString(); }
        };
    }

    static SourceFichier de(byte[] octets, String nom) {
        return new SourceFichier() {
            @Override public InputStream ouvrir() { return new ByteArrayInputStream(octets); }
            @Override public long taille() { return octets.length; }
            @Override public String nomOrigine() { return nom; }
        };
    }
}
