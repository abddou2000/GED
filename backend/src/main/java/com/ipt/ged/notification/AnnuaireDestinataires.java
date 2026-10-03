package com.ipt.ged.notification;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * <b>Point d'extension</b> : ce que les notifications ont besoin de savoir des
 * destinataires. Implémentation livrée : {@link AnnuaireDestinatairesIdentite}
 * (cache d'annuaire du lot E2, groupes et habilitations du lot E3) ; un autre
 * bean la remplace sans rien modifier ici.
 *
 * <p>Les identifiants sont ceux des identités GED ({@code utilisateur.id}), les
 * mêmes que {@code journal_audit.acteur_utilisateur_id} et que les sujets des
 * habilitations.
 */
public interface AnnuaireDestinataires {

    /** Adresse e-mail du destinataire ; vide = aucune adresse connue (pas d'e-mail, in-app seul). */
    Optional<String> courriel(UUID utilisateurId);

    /** Membres actuels d'un groupe GED (sujet {@code GROUPE} d'une habilitation). */
    Set<UUID> membresDuGroupe(UUID groupeId);

    /**
     * Espaces (identifiant, nom) sur lesquels un groupe porte un accès : un
     * membre ajouté au groupe reçoit l'accès à chacun (lot autorisation E3).
     */
    Map<UUID, String> espacesDuGroupe(UUID groupeId);

    /** Porteurs d'un rôle (ex. {@code AGENT_ARCHIVE} pour l'échéance de conservation). */
    Set<UUID> porteursDuRole(String codeRole);
}
