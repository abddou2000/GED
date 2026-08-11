package com.ipt.ged.document;

import com.ipt.ged.common.Auditable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Une version d'un document : un fichier de plus sous la même fiche.
 *
 * <p>Reprend {@code versions} de l'application d'origine. Le fichier lui-même
 * n'est jamais remplacé sur le disque : déposer une nouvelle version ajoute une
 * ligne et déplace le drapeau {@link #principale}, ce qui permet de revenir en
 * arrière — écraser le fichier rendrait l'ancienne version irrécupérable.
 */
@Entity
@Table(name = "document_versions")
@Getter
@Setter
@NoArgsConstructor
public class DocumentVersion extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private UploadDocument document;

    /** Nom du fichier d'origine tel que déposé. */
    @Column(name = "file_name", nullable = false)
    private String fileName;

    /** Chemin relatif du fichier sur le disque. */
    @Column(name = "file_path", nullable = false)
    private String filePath;

    private String extension;

    @Column(name = "size_ko")
    private long sizeKo;

    /** Motif du versement, saisi par l'opérateur. */
    @Column(columnDefinition = "text")
    private String observation;

    /** Version servie au téléchargement (une seule par document). */
    @Column(name = "is_default", nullable = false)
    private boolean principale = false;

    public DocumentVersion(UploadDocument document, String fileName, String filePath,
                           String extension, long sizeKo, String observation, boolean principale) {
        this.document = document;
        this.fileName = fileName;
        this.filePath = filePath;
        this.extension = extension;
        this.sizeKo = sizeKo;
        this.observation = observation;
        this.principale = principale;
    }
}
