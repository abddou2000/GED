package com.ipt.ged.fichier.integrite;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Fournit les empreintes enregistrées à vérifier.
 *
 * <p>À implémenter sur {@code version_document} ({@code fichier_id},
 * {@code empreinte}) une fois le modèle UUID intégré ; l'interface découple la
 * tâche de vérification du modèle JPA en cours de migration.
 */
public interface SourceEmpreintes {

    /** Parcourt toutes les empreintes à vérifier, sans les charger toutes en mémoire. */
    void parcourir(Consumer<EmpreinteAttendue> visiteur);

    /**
     * @param reference libellé lisible pour le journal et l'audit
     *                  (ex. identifiant de la version de document).
     */
    record EmpreinteAttendue(UUID fichierId, String empreinte, String reference) {
    }
}
