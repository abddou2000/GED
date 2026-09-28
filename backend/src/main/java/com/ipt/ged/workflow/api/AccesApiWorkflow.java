package com.ipt.ged.workflow.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;

import java.util.UUID;

/**
 * <b>Point d'extension pour le lot clés d'API (dev2, E8-API)</b> : les points
 * d'entrée du workflow sont les mêmes pour l'interface et pour l'intranet
 * (§2.3, D8). Ce contrat dit, pour la requête en cours :
 * <ul>
 *   <li>{@link #acteur} : au nom de qui l'opération est faite — l'utilisateur
 *       du jeton, ou pour une application l'identité déléguée
 *       ({@code X-On-Behalf-Of}, attribut {@code FiltreCleApi.ATTRIBUT_DELEGATION}) ;
 *       une application sans délégation ne peut rien faire au nom de
 *       personne : lever {@code AccessDeniedException} ;</li>
 *   <li>{@link #verifierPortee} : la portée de la clé autorise-t-elle
 *       l'opération sur ce nœud ({@code ControlePorteeApplication} avec
 *       {@code OperationApi.WORKFLOW_PILOTAGE} / {@code WORKFLOW_DECISION}) ?</li>
 * </ul>
 * L'implémentation livrée ({@code AccesApiWorkflowUtilisateurs}) ne connaît
 * que les utilisateurs ; dev2 déclare la sienne en {@code @Primary} et y
 * délègue pour un utilisateur. Idempotency-Key est appliqué par le filtre
 * de dev2 sur les requêtes d'écriture, sans rien demander au workflow.
 */
public interface AccesApiWorkflow {

    ActeurWorkflow acteur(Authentication authentification, HttpServletRequest requete);

    void verifierPortee(Authentication authentification, OperationWorkflow operation, UUID noeudId);
}
