package com.ipt.ged.fichier.stockage;

import java.util.UUID;

/**
 * Tentative d'écrire sur un identifiant déjà occupé. L'écriture unique est une
 * garantie du référentiel (§6.1.1) : écraser un fichier ferait disparaître une
 * version sans trace, et casserait l'empreinte enregistrée pour elle.
 */
public class FichierDejaPresentException extends IllegalStateException {

    private final UUID id;

    public FichierDejaPresentException(UUID id) {
        super("Le fichier " + id + " existe déjà : un fichier stocké n'est jamais réécrit.");
        this.id = id;
    }

    public UUID id() {
        return id;
    }
}
