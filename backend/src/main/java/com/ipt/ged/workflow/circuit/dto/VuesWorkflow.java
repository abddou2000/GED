package com.ipt.ged.workflow.circuit.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Corps et réponses secondaires de l'API du workflow (contrat E8-API), réunis
 * ici : ce sont de simples enregistrements sans logique.
 */
public final class VuesWorkflow {

    private VuesWorkflow() {
    }

    /** Décision : VALIDE, REFUSE (motif obligatoire) ou ANNULEE ; validateur facultatif. */
    public record DemandeDecision(@NotNull(message = "La décision est obligatoire") String decision, String motif,
                                  UUID validateurId) {}

    public record DemandeMotif(String motif) {}

    /** Réaffectation d'un validateur défaillant par l'Administrateur (D1, QR1). */
    public record DemandeReaffectation(@NotNull(message = "Le nouveau validateur est obligatoire") UUID employeId,
                                       String motif) {}

    /** Diffusion d'un document validé : lecture accordée, sans copie (§12.8). */
    public record DemandeDiffusion(List<UUID> utilisateurIds, List<UUID> groupeIds) {}

    public record ResultatDiffusion(int habilitationsPosees) {}

    /** Rattachement d'une règle à un nœud ou à un type ; {@code null} détache. */
    public record DemandeRattachement(UUID regleId) {}

    /** Règle qui s'appliquerait à un dépôt de ce document aujourd'hui. */
    public record RegleDocument(UUID regleId, String name, String origine, UUID origineId) {}

    /** Élément de la liste « à traiter » d'un validateur. */
    public record ATraiter(UUID circuitId, UUID documentId, String document, UUID validateurId, String libelle,
                           String type, Instant ouvertLe, String initiateur, Integer versionNumero) {}

    /** Décision rendue par l'acteur (historique). */
    public record DecisionRendue(UUID id, UUID circuitId, UUID documentId, String document, String libelle,
                                 String decision, String motif, Integer versionNumero, Instant le,
                                 String statutCircuit) {}

    /**
     * Validateur qui ne peut pas décider (tableau de bord de l'Administrateur) :
     * SANS_IDENTITE (jamais connecté à la GED), SANS_DROIT (plus de permission
     * Valider sur le document), INACTIF (aucune connexion depuis longtemps),
     * AUCUN_PORTEUR (rôle que personne ne détient sur le périmètre).
     */
    public record Anomalie(UUID circuitId, UUID documentId, String document, UUID validateurId, String libelle,
                           UUID employeId, String employe, String roleCode, String anomalie, Instant depuis) {}
}
