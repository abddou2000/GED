package com.ipt.ged.document;

import com.ipt.ged.common.Auditable;
import com.ipt.ged.typedocument.TypeDocument;
import com.ipt.ged.workspace.WorkSpace;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Document déposé dans la GED (Phase 1 : fiche + fichier stocké sur disque).
 * Reproduit le cœur de {@code UploadDocument} de CCISTTA. Les versions, valeurs
 * d'index, étiquettes et le circuit de signature seront ajoutés en phases suivantes.
 */
@Entity
@Table(name = "documents_file")
@Getter
@Setter
@NoArgsConstructor
public class UploadDocument extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "workspace_id", nullable = false)
    private WorkSpace workspace;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "type_document_id", nullable = false)
    private TypeDocument typeDocument;

    /** Nom du fichier stocké sur disque (unique). */
    @Column(name = "file_name")
    private String fileName;

    /** Chemin relatif du fichier sur le disque. */
    @Column(name = "file_path")
    private String filePath;

    private String extension;

    /** Taille en kilo-octets. */
    @Column(name = "size_ko")
    private long sizeKo;

    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    /**
     * Référence composée à partir du plan d'indexation (index ordonnés, séparateur,
     * casse). Renseignée seulement lorsqu'un opérateur a confirmé l'indexation.
     */
    @Column(name = "reference")
    private String reference;

    /** Document validé / utilisable (true tant qu'aucun circuit de signature ne le bloque). */
    @Column(nullable = false)
    private boolean active = true;

    /** Corbeille : true = supprimé de façon réversible. */
    @Column(nullable = false)
    private boolean deleted = false;

    public UploadDocument(String name) {
        this.name = name;
    }
}
