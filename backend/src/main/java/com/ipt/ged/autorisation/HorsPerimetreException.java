package com.ipt.ged.autorisation;

import jakarta.persistence.EntityNotFoundException;

/**
 * Objet hors du périmètre de l'appelant : rendu 404, comme un objet inexistant
 * (P5) — le message ne dit jamais que l'objet existe. Hérite de
 * {@link EntityNotFoundException} pour emprunter la traduction HTTP commune.
 */
public class HorsPerimetreException extends EntityNotFoundException {

    public HorsPerimetreException(String message) {
        super(message);
    }
}
