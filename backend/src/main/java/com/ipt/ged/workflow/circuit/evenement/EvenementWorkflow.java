package com.ipt.ged.workflow.circuit.evenement;

import com.ipt.ged.audit.EvenementAudit;
import com.ipt.ged.notification.DemandeNotification;
import com.ipt.ged.notification.EvenementNotifiable;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Événement du workflow de validation (§12.8) : ouverture, décision,
 * annulation, réaffectation d'un validateur, diffusion. Publié dans la
 * transaction de l'écriture ; il implémente les deux contrats de dev2 :
 * {@link EvenementAudit} (journal, écrit dans la même transaction) et
 * {@link EvenementNotifiable} (boîte d'envoi des notifications). Une seule
 * publication par action : l'audit et la notification partent du même objet.
 *
 * <p>L'acteur est la personne nommée au nom de laquelle l'action est faite ;
 * pour une application (clé d'API, D8), son identifiant est tracé en plus
 * (double identité dans l'audit).
 *
 * @param action       code d'audit ({@code CIRCUIT_OUVERT}, {@code VALIDATION_APPROUVEE}…)
 * @param objetId      document concerné (le circuit figure dans l'après), nœud ou type (règle)
 * @param objetType    {@code DOCUMENT}, {@code NOEUD} ou {@code TYPE_DOCUMENT}
 * @param avant        état avant (champs modifiés), ou {@code null}
 * @param apres        état après, ou valeurs créées
 * @param motifAction  motif saisi (refus, annulation, réaffectation)
 * @param utilisateurId identité GED de l'acteur
 * @param applicationId application appelante, ou {@code null}
 * @param nomActeur    libellé de l'acteur
 * @param demande      notification à écrire, ou {@code null}
 */
public record EvenementWorkflow(String action, UUID objetId, String objetType, Map<String, Object> avant,
                                Map<String, Object> apres, String motifAction, UUID utilisateurId,
                                UUID applicationId, String nomActeur, DemandeNotification demande,
                                Instant instant) implements EvenementAudit, EvenementNotifiable {

    public static final String CIRCUIT_OUVERT = "CIRCUIT_OUVERT";
    /** Nouveau circuit ouvert à la main après une annulation (catalogue de dev2). */
    public static final String VALIDATION_RELANCEE = "VALIDATION_RELANCEE";
    public static final String VALIDATION_APPROUVEE = "VALIDATION_APPROUVEE";
    public static final String VALIDATION_REJETEE = "VALIDATION_REJETEE";
    /** Le validateur retire sa décision (code du lot). */
    public static final String DECISION_ANNULEE = "DECISION_ANNULEE";
    public static final String CIRCUIT_ANNULE = "CIRCUIT_ANNULE";
    /** Réaffectation manuelle par l'Administrateur (D1, QR1 ; code du lot). */
    public static final String VALIDATEUR_REAFFECTE = "VALIDATEUR_REAFFECTE";
    /** Diffusion d'un document validé : habilitations de lecture posées (code du lot). */
    public static final String DOCUMENT_DIFFUSE = "DOCUMENT_DIFFUSE";

    @Override
    public String motif() { return motifAction; }

    @Override
    public UUID acteurUtilisateurId() { return utilisateurId; }

    @Override
    public UUID acteurApplicationId() { return applicationId; }

    @Override
    public String acteurNom() { return nomActeur; }

    @Override
    public DemandeNotification notification() { return demande; }
}
