package com.ipt.ged.workflow.api;

import java.util.UUID;

/**
 * La personne au nom de qui une opération de workflow est faite (§12.8, D8) :
 * l'utilisateur authentifié, ou l'identité déléguée par une application
 * ({@code X-On-Behalf-Of}). Toute action du workflow est attribuée à une
 * personne nommée ; l'application, s'il y en a une, est tracée en plus.
 *
 * @param utilisateurId identité GED de la personne
 * @param employeId     personne métier (validateur nommé)
 * @param applicationId application appelante ; {@code null} pour l'interface
 * @param libelle       nom lisible
 */
public record ActeurWorkflow(UUID utilisateurId, UUID employeId, UUID applicationId, String libelle) {
}
