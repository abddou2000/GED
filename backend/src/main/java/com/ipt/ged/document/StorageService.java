package com.ipt.ged.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Stockage des fichiers de la GED sur le disque local. Chaque dossier (workspace)
 * a son sous-répertoire ; le fichier est stocké sous un nom unique (UUID).
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final Path root;

    public StorageService(@Value("${ged.storage.root}") String rootDir) {
        this.root = Paths.get(rootDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de créer le dossier de stockage : " + root, e);
        }
    }

    /** Enregistre le fichier et renvoie son chemin relatif ({workspaceId}/{uuid.ext}). */
    public String store(MultipartFile file, Long workspaceId, String ext) {
        try {
            Path dir = root.resolve(String.valueOf(workspaceId));
            Files.createDirectories(dir);
            String stored = UUID.randomUUID() + (ext != null && !ext.isBlank() ? "." + ext : "");
            Path target = dir.resolve(stored).normalize();
            file.transferTo(target);
            return workspaceId + "/" + stored;
        } catch (IOException e) {
            throw new IllegalStateException("Échec de l'enregistrement du fichier", e);
        }
    }

    /** Chemin disque d'un fichier stocké — l'OCRisation lit le fichier directement. */
    public Path chemin(String relativePath) {
        return root.resolve(relativePath).normalize();
    }

    /**
     * Supprime un fichier stocké. Sert exclusivement à <b>compenser</b> une
     * transaction annulée : le disque n'est pas transactionnel, un dépôt qui
     * échoue après {@link #store} laissait sinon un fichier orphelin que rien
     * dans l'application ne référençait — et qu'aucune API ne permettait
     * d'effacer, {@code StorageService} n'ayant jamais exposé de suppression.
     *
     * <p>Volontairement silencieuse : elle s'exécute après coup, alors que la
     * réponse est déjà partie. Échouer bruyamment ici masquerait l'erreur
     * d'origine, la seule qui intéresse l'appelant.
     *
     * <p>Le chemin est confiné sous la racine avant toute suppression : un
     * chemin relatif remontant ({@code ../}) effacerait un fichier hors GED.
     *
     * @return {@code true} si un fichier a effectivement été supprimé.
     */
    public boolean supprimer(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return false;
        try {
            Path cible = root.resolve(relativePath).normalize();
            if (!cible.startsWith(root)) {
                log.warn("Suppression refusée, chemin hors du stockage : {}", relativePath);
                return false;
            }
            return Files.deleteIfExists(cible);
        } catch (Exception e) {
            log.warn("Fichier orphelin non supprimé : {}", relativePath, e);
            return false;
        }
    }

    /** Charge un fichier stocké pour le téléchargement. */
    public Resource load(String relativePath) {
        try {
            Path file = root.resolve(relativePath).normalize();
            Resource resource = new UrlResource(file.toUri());
            if (resource.exists() && resource.isReadable()) {
                return resource;
            }
            throw new IllegalStateException("Fichier introuvable : " + relativePath);
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Chemin de fichier invalide : " + relativePath, e);
        }
    }
}
