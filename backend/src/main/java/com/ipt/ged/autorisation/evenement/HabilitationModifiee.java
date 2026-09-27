package com.ipt.ged.autorisation.evenement;

import com.ipt.ged.audit.EvenementAudit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Modification du modèle de droits (dossier technique §12.2.3) : attribution,
 * retrait, rupture d'héritage, composition d'un rôle, membres d'un groupe GED.
 * Publiée comme événement Spring APRÈS l'écriture et dans sa transaction ; le
 * journal d'audit (dev2, contrat {@link EvenementAudit}) l'enregistre dans la
 * même transaction, action {@code HABILITATION_MODIFIEE}, avec les valeurs
 * avant et après.
 *
 * <p>Toute modification est effective immédiatement (compteur
 * {@code version_habilitations}) : l'événement n'est pas une demande
 * d'application, seulement une trace.
 *
 * @param objet     HABILITATION, ROLE ou GROUPE_GED
 * @param operation AJOUT, RETRAIT ou MODIFICATION
 * @param objetId   identifiant de la ligne concernée (habilitation, rôle, groupe)
 * @param avant     état avant ({@code null} pour un ajout)
 * @param apres     état après ({@code null} pour un retrait)
 * @param auteurId  identité GED de l'auteur ({@code null} : amorçage)
 */
public record HabilitationModifiee(String objet, String operation, UUID objetId,
                                   Map<String, Object> avant, Map<String, Object> apres,
                                   UUID auteurId, Instant instant) implements EvenementAudit {

    public static final String HABILITATION = "HABILITATION";
    public static final String ROLE = "ROLE";
    public static final String GROUPE_GED = "GROUPE_GED";

    public static final String AJOUT = "AJOUT";
    public static final String RETRAIT = "RETRAIT";
    public static final String MODIFICATION = "MODIFICATION";

    @Override
    public String action() { return "HABILITATION_MODIFIEE"; }

    @Override
    public String objetType() { return objet; }

    @Override
    public UUID objetId() { return objetId; }

    /** L'opération (AJOUT, RETRAIT, MODIFICATION) complète l'action. */
    @Override
    public String motif() { return operation; }

    /** {@code null} (amorçage) = l'acteur de la requête, s'il y en a un. */
    @Override
    public UUID acteurUtilisateurId() { return auteurId; }
}
