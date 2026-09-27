package com.ipt.ged.ocr.moteur;

import java.time.Duration;
import java.util.Set;

/**
 * Moteur de reconnaissance optique d'<b>une page</b> (§4.3.2 : « le worker
 * s'appuie sur une interface {@code OcrEngine} : un changement de moteur n'a
 * aucun autre impact sur l'architecture »).
 *
 * <p>L'image est transmise en mémoire et le texte rendu en mémoire : aucune
 * implémentation ne doit poser la page rendue sur un disque persistant
 * (§4.3.4).
 */
public interface OcrEngine {

    String nom();

    /** Le moteur répond-il sur ce serveur ? */
    boolean disponible();

    /** Codes de langue installés (ex. {@code fra}, {@code ara}). */
    Set<String> languesInstallees();

    /**
     * Reconnaît le texte d'une page.
     *
     * @param image  image encodée (PNG, JPEG, TIFF…), en mémoire ;
     * @param langue combinaison de modèles, ex. {@code fra+ara} ;
     * @param delai  délai maximal pour cette page (60 s par défaut) ; au-delà,
     *               le moteur est arrêté et {@link EchecOcrException.Motif#DELAI_DEPASSE} levé.
     * @return texte dans l'ordre logique des caractères (l'arabe n'est pas inversé).
     */
    String reconnaitre(byte[] image, String langue, Duration delai) throws EchecOcrException;
}
