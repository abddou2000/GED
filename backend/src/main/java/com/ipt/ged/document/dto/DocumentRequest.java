package com.ipt.ged.document.dto;

import java.util.List;
import java.util.UUID;

/**
 * Donnees recues pour modifier la fiche d'un document deja depose.
 *
 * <p>Tous les champs sont facultatifs : l'ecran d'edition n'envoie que ce que
 * l'operateur a touche, et exiger le formulaire complet ecraserait des valeurs
 * qu'il n'a pas vues.
 */
public record DocumentRequest(
        String name,
        UUID typeDocumentId,
        String expirationDate,
        Boolean active,
        List<UUID> etiquetteIds,
        /** Nouveau niveau de confidentialité (§12.3) ; absent = inchangé. */
        com.ipt.ged.autorisation.Confidentialite confidentialite
) {}
