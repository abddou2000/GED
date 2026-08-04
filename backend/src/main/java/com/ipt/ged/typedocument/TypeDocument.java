package com.ipt.ged.typedocument;

import com.ipt.ged.common.Auditable;
import com.ipt.ged.planindexation.PlanIndexation;
import com.ipt.ged.workspace.WorkSpace;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Type de document : définit, pour un espace de travail, un genre de document
 * autorisé et ses contraintes de dépôt (formats + taille max), avec le plan
 * d'indexation à appliquer. Reproduit {@code TypeDeDocument} de CCISTTA.
 */
@Entity
@Table(name = "type_de_documents")
@Getter
@Setter
@NoArgsConstructor
public class TypeDocument extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    /** Libellé du type (ex. « Facture »). */
    @Column(name = "type_de_document", nullable = false)
    private String typeDeDocument;

    @Column(columnDefinition = "text", nullable = false)
    private String description;

    /** Dossier auquel ce type est rattaché (un type appartient à un seul dossier). */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "workspace_id", nullable = false)
    private WorkSpace workspace;

    /** Plan d'indexation appliqué (facultatif). */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "plan_d_indexation_id")
    private PlanIndexation planIndexation;

    /** Formats de fichier autorisés (séparés par des virgules : pdf,docx…). */
    @Column(name = "type_autorise")
    private String typeAutorise;

    @Column(name = "taille_max_mo", nullable = false)
    private int tailleMaxMo;

    /**
     * Ce type déclenche-t-il la lecture du contenu (OCR) à l'indexation ?
     * Vrai par défaut : un type nouvellement créé profite de l'automatisme sans
     * réglage préalable. À couper pour les types dont le contenu n'apporte rien
     * — le rendu d'un scan coûte cher pour un résultat prévisible.
     */
    /** Corbeille : true = supprimé de façon réversible. */
    @Column(nullable = false)
    private boolean deleted = false;

    public TypeDocument(String code, String typeDeDocument) {
        this.code = code;
        this.typeDeDocument = typeDeDocument;
    }
}
