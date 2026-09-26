package com.ipt.ged.recherche;

import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

/**
 * Index plein texte (§4.4). Implémentation livrée : PostgreSQL
 * ({@code tsvector}, index GIN, configurations {@code french} et
 * {@code arabic}). L'interface isole ce choix : une bascule vers un moteur
 * dédié resterait possible si la volumétrie dépassait dix fois l'hypothèse
 * du §6.6.
 */
public interface SearchIndexer {

    /**
     * Indexation incrémentale : enregistre le texte de la version courante
     * d'un document et remplace celui de toute version précédente. À appeler
     * dans la transaction qui clôt le job OCR.
     */
    void indexer(TexteAIndexer texte);

    /** Retire le texte d'un document (purge définitive). */
    boolean supprimer(UUID documentId);

    /** Recherche filtrée par droits à la source, paginée. */
    PageResultats rechercher(RequeteRecherche requete, Authentication utilisateur);

    /** Versions indexées parmi celles données (document « interrogeable » ou non). */
    List<UUID> versionsIndexees(List<UUID> versionIds);

    /** Nombre de documents indexés. */
    long compter();

    /**
     * Réindexation complète, un lot : recalcule le vecteur de {@code taille}
     * documents d'identifiant de version supérieur à {@code apres}.
     *
     * @return identifiants traités, dans l'ordre (vide = fin).
     */
    List<UUID> reindexerLot(UUID apres, int taille);

    record TexteAIndexer(UUID documentId, UUID versionId, String langue, String texte, String provenance,
                         int nbPages) {
    }
}
