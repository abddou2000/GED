package com.ipt.ged.fichier.stockage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Référentiel de fichiers hors base (§6.1.1) : un fichier par identifiant
 * opaque, écrit une seule fois.
 *
 * <p>Le contrat est volontairement réduit à ce qu'un stockage objet sait aussi
 * garantir, pour qu'une implémentation S3 (non livrée) puisse remplacer le
 * disque sans toucher aux appelants :
 * <ul>
 *   <li><b>écriture unique</b> : {@link #ecrire} refuse un identifiant déjà
 *       présent (S3 : {@code If-None-Match: *}) ;</li>
 *   <li><b>publication atomique</b> : un lecteur voit le fichier complet ou
 *       rien (S3 : un envoi multipart n'existe qu'à sa complétion) ;</li>
 *   <li><b>lecture en flux</b> : rien n'est chargé entièrement en mémoire.</li>
 * </ul>
 *
 * <p>Le référentiel ne connaît que des octets opaques : le chiffrement est posé
 * au-dessus ({@code StockageChiffre}), si bien qu'aucune implémentation ne voit
 * jamais un contenu en clair.
 */
public interface FileStore {

    /**
     * Écrit un nouveau fichier. L'écrivain reçoit un flux qu'il remplit ; si
     * l'écrivain lève une exception, rien n'est publié.
     *
     * @throws FichierDejaPresentException si l'identifiant existe déjà.
     */
    void ecrire(UUID id, EcrivainFlux ecrivain) throws IOException;

    /** Commodité : recopie un flux existant. */
    default void ecrire(UUID id, InputStream contenu) throws IOException {
        ecrire(id, contenu::transferTo);
    }

    /**
     * Ouvre le fichier en lecture, en flux. L'appelant ferme le flux.
     *
     * @throws com.ipt.ged.fichier.ErreurFichierException (404) si absent.
     */
    InputStream lire(UUID id) throws IOException;

    boolean existe(UUID id);

    /** Taille stockée, en octets (chiffrée, donc supérieure à la taille en clair). */
    long taille(UUID id) throws IOException;

    /**
     * Supprime le fichier. Réservé à la purge définitive et à la compensation
     * d'un dépôt annulé : un fichier publié n'est jamais modifié.
     *
     * @return {@code true} si un fichier a été supprimé.
     */
    boolean supprimer(UUID id) throws IOException;

    /** Remplit le flux d'un fichier en cours d'écriture. */
    @FunctionalInterface
    interface EcrivainFlux {
        void ecrire(OutputStream sortie) throws IOException;
    }
}
