package com.ipt.ged.depot;

import com.ipt.ged.indexation.IndexationService;
import com.ipt.ged.indexation.dto.ValeurRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Temps 2 du dépôt (§12.11) : enregistrement des métadonnées du plan dans une
 * transaction <b>séparée</b> de celle du temps 1. Mêmes règles et même audit
 * que la saisie par l'écran d'indexation ({@link IndexationService#enregistrer}),
 * qui fait passer l'issue du document à {@code INDEXE}.
 */
@Component
public class IndexationAuDepot {

    private final IndexationService indexation;

    public IndexationAuDepot(IndexationService indexation) {
        this.indexation = indexation;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void indexer(UUID documentId, ValeurRequest valeurs) {
        indexation.enregistrer(documentId, valeurs);
    }

    /** Variante sans transaction propre, pour un appelant déjà transactionnel. */
    public void indexerDansLaTransaction(UUID documentId, ValeurRequest valeurs) {
        indexation.enregistrer(documentId, valeurs);
    }
}
