package com.ipt.ged.document;

import com.ipt.ged.common.IdentifiantUuid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Personne désignée pour un document CONFIDENTIEL (dossier technique §12.3,
 * table {@code document_confidentiel_designe}). La désignation ne donne aucun
 * droit d'emplacement : l'accès effectif reste une intersection.
 */
@Entity
@Table(name = "document_confidentiel_designe")
@Getter
@NoArgsConstructor
public class DocumentConfidentielDesigne {

    @Id
    @IdentifiantUuid
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "utilisateur_id", nullable = false)
    private UUID utilisateurId;

    @Column(name = "cree_par")
    private UUID creePar;

    @Column(name = "cree_le", nullable = false)
    private Instant creeLe = Instant.now();

    public DocumentConfidentielDesigne(UUID documentId, UUID utilisateurId, UUID creePar) {
        this.documentId = documentId;
        this.utilisateurId = utilisateurId;
        this.creePar = creePar;
    }
}
