package com.ipt.ged.document;

import com.ipt.ged.common.IdentifiantUuid;
import com.ipt.ged.workspace.WorkSpace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Emplacement complémentaire d'un document (dossier technique §12.4).
 *
 * <p>Il n'existe qu'UN enregistrement {@code document}, une seule chaîne de
 * versions et un seul jeu de métadonnées ; chaque rattachement est une ligne,
 * unique par (document, nœud). Les droits du document sont l'union de ceux de
 * ses emplacements, sous réserve de son niveau de confidentialité.
 */
@Entity
@Table(name = "document_rattachement")
@Getter
@NoArgsConstructor
public class DocumentRattachement {

    @Id
    @IdentifiantUuid
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private UploadDocument document;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "noeud_id", nullable = false)
    private WorkSpace noeud;

    /** Identité GED de l'auteur du rattachement. */
    @Column(name = "cree_par")
    private UUID creePar;

    @Column(name = "cree_le", nullable = false)
    private Instant creeLe = Instant.now();

    public DocumentRattachement(UploadDocument document, WorkSpace noeud, UUID creePar) {
        this.document = document;
        this.noeud = noeud;
        this.creePar = creePar;
    }
}
