package com.ipt.ged.workflow.circuit.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Circuit de validation tel que l'interface et l'intranet le lisent (contrat
 * E8-API). Aucun ordre d'étape : les validateurs sont listés dans l'ordre
 * d'affichage de la règle d'origine, tous sollicités en même temps (D7).
 *
 * @param statut                EN_COURS, VALIDE, REFUSE ou ANNULE
 * @param versionCouranteId     version sur laquelle portent les décisions qui comptent
 * @param peutDecider           l'acteur peut-il rendre une décision maintenant ?
 * @param peutAnnuler           l'acteur (initiateur ou Administrateur) peut-il annuler ?
 */
public record CircuitResponse(UUID id, UUID documentId, String document, String statut, UUID regleId, String regle,
                              String initiateur, Instant ouvertLe, Instant closLe, String annulePar,
                              Instant annuleLe, String motifAnnulation, UUID versionCouranteId,
                              Integer versionCouranteNumero, List<Validateur> validateurs,
                              List<DecisionVue> decisions, boolean peutDecider, boolean peutAnnuler) {

    /**
     * @param type             NOMME ou ROLE
     * @param etat             EN_ATTENTE, VALIDE ou REFUSE, sur la version courante
     * @param derniereDecision instant de la dernière décision sur la version courante
     * @param reaffecteDe      validateur remplacé par l'Administrateur, s'il y a lieu
     */
    public record Validateur(UUID id, String type, UUID employeId, String employe, String roleCode,
                             UUID perimetreNoeudId, String libelle, String etat, Instant derniereDecision,
                             String reaffecteDe, String reaffectePar, Instant reaffecteLe,
                             String motifReaffectation) {}

    /** Une décision, jamais modifiée ; {@code caduque} si elle porte sur une version antérieure. */
    public record DecisionVue(UUID id, UUID validateurId, UUID versionId, Integer versionNumero, String decision,
                              String motif, String auteur, UUID applicationId, Instant le, boolean caduque) {}
}
