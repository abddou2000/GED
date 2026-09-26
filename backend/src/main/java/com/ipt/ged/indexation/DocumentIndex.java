package com.ipt.ged.indexation;

import com.ipt.ged.common.IdentifiantUuid;
import java.util.UUID;
import com.ipt.ged.common.Auditable;
import com.ipt.ged.document.UploadDocument;
import com.ipt.ged.index.IndexField;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Valeur d'un index pour un document donné.
 *
 * C'est la pièce qui manquait : jusqu'ici les index et les plans d'indexation
 * n'existaient qu'en configuration — aucune valeur n'était conservée. Cette table
 * relie un document, un index et sa valeur ; elle rend enfin effectives les options
 * « obligatoire », « indexé pour recherche » et « index de groupage ».
 *
 * Les valeurs sont stockées en texte, quel que soit le type de l'index. Les dates
 * le sont au format ISO (AAAA-MM-JJ) afin que l'ordre alphabétique corresponde à
 * l'ordre chronologique — ce qui permet les recherches par plage.
 */
@Entity
@Table(
    name = "document_index_valeur",
    uniqueConstraints = @UniqueConstraint(name = "uk_document_index_valeur_document_id_index_def_id", columnNames = {"document_id", "index_def_id"})
)
@Getter
@Setter
@NoArgsConstructor
public class DocumentIndex extends Auditable {

    @Id
    @IdentifiantUuid
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private UploadDocument document;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "index_def_id", nullable = false)
    private IndexField indexField;

    @Column(columnDefinition = "text")
    private String valeur;

    public DocumentIndex(UploadDocument document, IndexField indexField, String valeur) {
        this.document = document;
        this.indexField = indexField;
        this.valeur = valeur;
    }
}
