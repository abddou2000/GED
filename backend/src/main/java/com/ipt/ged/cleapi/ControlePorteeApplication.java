package com.ipt.ged.cleapi;

import java.util.UUID;

/**
 * Point d'extension de la portée d'une clé (DAT §5.4, table
 * {@code cle_api_portee}) : l'application peut-elle faire cette opération sur
 * ce nœud ?
 *
 * <p>Une clé d'API est un sujet comme un autre (§12.2) : sa portée équivaut à
 * des attributions assorties d'une liste d'opérations, résolues par le même
 * point d'application unique que pour les utilisateurs ({@code AccessPredicate},
 * lot autorisation E3). L'implémentation qui interroge {@code cle_api_portee}
 * par ce point unique est livrée en vague 4 ; d'ici là,
 * {@link ConfigurationCleApi} déclare une implémentation qui ne restreint rien
 * de plus que l'authentification, comme pour les utilisateurs aujourd'hui.
 *
 * <p>Contrat : lever {@link com.ipt.ged.common.erreur.RessourceIntrouvableException}
 * pour un nœud hors portée (un objet non visible n'est jamais distingué d'un
 * objet absent) et {@link com.ipt.ged.common.erreur.AccesRefuseException} pour
 * un nœud visible dont l'opération n'est pas autorisée.
 */
@FunctionalInterface
public interface ControlePorteeApplication {

    void verifier(ApplicationAuthentifiee application, OperationApi operation, UUID noeudId);
}
