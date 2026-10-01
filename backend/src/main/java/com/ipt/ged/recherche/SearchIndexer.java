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

    /**
     * Plage du texte indexé d'un document (version courante), s'il l'est : au
     * plus {@code longueur} caractères à partir du rang {@code debut} (à partir
     * de 0). Seule la plage est lue : un texte de 150 Mo ne transite jamais
     * entier par l'application.
     */
    java.util.Optional<TexteIndexe> texte(UUID documentId, int debut, int longueur);

    /**
     * @param texte        la plage demandée (vide au-delà de la fin) ;
     * @param debut        rang de son premier caractère ;
     * @param suite        vrai si du texte suit la plage ;
     * @param tailleOctets taille du texte entier en UTF-8.
     */
    record TexteIndexe(UUID documentId, UUID versionId, String langue, String provenance, Integer nbPages,
                       java.time.Instant indexeLe, String texte, int debut, boolean suite, long tailleOctets) {
    }

    record TexteAIndexer(UUID documentId, UUID versionId, String langue, String texte, String provenance,
                         int nbPages) {
    }
}
