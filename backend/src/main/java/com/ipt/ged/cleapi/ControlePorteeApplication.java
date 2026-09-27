package com.ipt.ged.cleapi;

import java.util.UUID;

/**
 * Point d'extension de la portée d'une clé (DAT §5.4, table
 * {@code cle_api_portee}) : l'application peut-elle faire cette opération sur
 * ce nœud ?
 *
 * <p>Une clé d'API est un sujet comme un autre (§12.2) : sa portée est traduite
 * en attributions par {@link SourceHabilitationsApplications} et appliquée
 * partout par le point d'application unique ({@code AccessPredicate}, lot E3).
 * Cette interface sert aux contrôles explicites d'une opération sur un nœud ;
 * {@link ConfigurationCleApi} la branche sur {@code ControleAcces}.
 *
 * <p>Contrat : 404 pour un nœud hors portée (un objet non visible n'est jamais
 * distingué d'un objet absent), 403 pour un nœud visible dont l'opération n'est
 * pas autorisée.
 */
@FunctionalInterface
public interface ControlePorteeApplication {

    void verifier(ApplicationAuthentifiee application, OperationApi operation, UUID noeudId);
}
