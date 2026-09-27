package com.ipt.ged.ocr.file;

import com.ipt.ged.ocr.moteur.EchecOcrException;

import java.io.InputStream;
import java.util.UUID;

/**
 * Accès au contenu <b>déchiffré</b> d'un fichier stocké, en flux et en mémoire
 * (§6.1.3 : le worker déchiffre en mémoire, jamais sur disque persistant).
 * Branché sur {@code StockageChiffre} du lot E5.
 */
@FunctionalInterface
public interface SourceFichierOcr {

    /** @throws EchecOcrException fichier absent (purgé) ou altéré : échec définitif. */
    InputStream ouvrir(UUID fichierId) throws EchecOcrException;
}
