package com.ipt.ged.typedocument;

import com.ipt.ged.autorisation.Confidentialite;
import com.ipt.ged.common.IdentifiantUuid;
import com.ipt.ged.common.Supprimable;
import com.ipt.ged.planindexation.PlanIndexation;
import com.ipt.ged.workspace.WorkSpace;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Type de document : définit, pour un espace de travail, un genre de document
 * autorisé et ses contraintes de dépôt (formats + taille max), avec le plan
 * d'indexation à appliquer. Reproduit {@code TypeDeDocument} de CCISTTA.
 */
@Entity
@Table(name = "type_document")
@Getter
@Setter
@NoArgsConstructor
public class TypeDocument extends Supprimable {

    @Id
    @IdentifiantUuid
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    /** Libellé du type (ex. « Facture »). */
    @Column(name = "type_de_document", nullable = false)
    private String typeDeDocument;

    @Column(columnDefinition = "text", nullable = false)
    private String description;

    /** Dossier auquel ce type est rattaché (un type appartient à un seul dossier). */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "noeud_id", nullable = false)
    private WorkSpace workspace;

    /** Niveau de confidentialité donné par défaut aux documents de ce type (§12.3). */
    @Enumerated(EnumType.STRING)
    @Column(name = "confidentialite_defaut", nullable = false)
    private Confidentialite confidentialiteDefaut = Confidentialite.PUBLIC;

    /** Plan d'indexation appliqué (facultatif). */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "plan_indexation_id")
    private PlanIndexation planIndexation;

    /** Formats de fichier autorisés (séparés par des virgules : pdf,docx…). */
    @Column(name = "type_autorise")
    private String typeAutorise;

    @Column(name = "taille_max_mo", nullable = false)
    private int tailleMaxMo;

    public TypeDocument(String code, String typeDeDocument) {
        this.code = code;
        this.typeDeDocument = typeDeDocument;
    }

    /**
     * Formats acceptes, eclates depuis la colonne stockee en CSV.
     *
     * <p>Le decoupage vivait en double dans les services, en methode privee :
     * deux endroits a corriger le jour ou la separation change. Il appartient a
     * l'entite, seule a savoir comment sa colonne est ecrite.</p>
     *
     * @return la liste en minuscules, vide si le type n'impose aucun format.
     */
    public java.util.List<String> formatsAutorises() {
        if (typeAutorise == null || typeAutorise.isBlank()) return java.util.List.of();
        return java.util.Arrays.stream(typeAutorise.split(","))
                .map(s -> s.trim().toLowerCase())
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
