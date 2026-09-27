package com.ipt.ged.cleapi;

import com.ipt.ged.autorisation.CodePermission;

import java.util.EnumSet;
import java.util.Set;

import static com.ipt.ged.autorisation.CodePermission.CONSULTER;
import static com.ipt.ged.autorisation.CodePermission.DEPOSER;
import static com.ipt.ged.autorisation.CodePermission.MODIFIER;
import static com.ipt.ged.autorisation.CodePermission.VALIDER;

/**
 * Opérations qu'une portée de clé peut autoriser (DAT §5.4 : « création de
 * dossier, dépôt, consultation, recherche, versement, rattachement,
 * consultation des droits »), et leur traduction en permissions élémentaires
 * du modèle de droits (§12.2.1) : une clé est un sujet comme un autre, sa
 * portée est décidée par le même point d'application unique ({@code AccessPredicate}).
 *
 * <p>Une opération sur un document existant (versement, rattachement) inclut
 * {@code CONSULTER} : un document invisible est « introuvable » (P5), on ne peut
 * donc pas y agir sans le voir.
 *
 * <p>Opérations de circuit de validation (revue technique D8, contrat E8-API de
 * dev1, {@code /api/v1/workflow}) : {@code PILOTAGE} ouvre, annule un circuit
 * et désigne les validateurs (il modifie l'état du document : Modifier),
 * {@code DECISION} rend une décision (Valider). Le nœud évalué est
 * l'emplacement principal du document ; la personne déléguée doit en outre
 * détenir elle-même les permissions (contrôle du workflow).
 */
public enum OperationApi {
    /** Créer un dossier sous le nœud (Déposer sur le parent). */
    CREATION_DOSSIER(DEPOSER),
    DEPOT(DEPOSER),
    CONSULTATION(CONSULTER),
    RECHERCHE(CONSULTER),
    /** Ajouter une version à un document du nœud. */
    VERSEMENT(CONSULTER, MODIFIER),
    /** Rattacher un document du nœud ailleurs, ou un document à ce nœud. */
    RATTACHEMENT(CONSULTER, MODIFIER, DEPOSER),
    CONSULTATION_DROITS(CONSULTER),

    // --- D8, lot E8-API (vague 5) ---
    /** Désigner les validateurs, ouvrir et annuler un circuit. */
    WORKFLOW_PILOTAGE(CONSULTER, MODIFIER),
    /** Rendre une décision de validation (pour le compte d'un validateur délégué). */
    WORKFLOW_DECISION(CONSULTER, VALIDER);

    private final Set<CodePermission> permissions;

    OperationApi(CodePermission premiere, CodePermission... autres) {
        this.permissions = EnumSet.of(premiere, autres);
    }

    /** Permissions élémentaires que l'opération accorde sur le nœud de la portée. */
    public Set<CodePermission> permissions() {
        return EnumSet.copyOf(permissions);
    }

    /** Permissions accordées par un ensemble d'opérations. */
    public static Set<CodePermission> permissions(Set<OperationApi> operations) {
        EnumSet<CodePermission> p = EnumSet.noneOf(CodePermission.class);
        operations.forEach(o -> p.addAll(o.permissions));
        return p;
    }
}
