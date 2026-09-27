package com.ipt.ged.document;

import com.ipt.ged.common.IdentifiantUuid;
import com.ipt.ged.common.Auditable;
import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Une version d'un document : un fichier de plus sous la même fiche.
 *
 * <p>Reprend {@code versions} de l'application d'origine. Le fichier lui-même
 * n'est jamais remplacé sur le disque : déposer une nouvelle version ajoute une
 * ligne et déplace le drapeau {@link #principale}, ce qui permet de revenir en
 * arrière — écraser le fichier rendrait l'ancienne version irrécupérable.
 */
@Entity
@Table(name = "version_document")
@Getter
@Setter
@NoArgsConstructor
public class DocumentVersion extends Auditable {

    @Id
    @IdentifiantUuid
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private UploadDocument document;

    /** Nom du fichier d'origine tel que déposé. */
    @Column(name = "file_name", nullable = false)
    private String fileName;

    /**
     * Fichier chiffré de la version (§6.1.1, §6.1.2) : même identifiant que sa
     * clé dans {@code cle_fichier} et que son nom {@code aa/bb/<id>.enc}. Le
     * chemin en clair de l'ancien stockage n'est plus mappé : il ne sert qu'à
     * la reprise (JDBC) avant d'être supprimé par le changeset « contract ».
     */
    @Column(name = "cle_fichier_id")
    private UUID cleFichierId;

    /** SHA-256 du contenu en clair, hexadécimal minuscule (§6.1.4). */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "empreinte", length = 64)
    private String empreinte;

    /** Type réel détecté par le contenu au dépôt (§6.1.5). */
    @Column(name = "type_mime", length = 127)
    private String typeMime;

    /** Taille du contenu en clair, en octets. */
    @Column(name = "taille_octets")
    private Long tailleOctets;

    private String extension;

    @Column(name = "size_ko")
    private long sizeKo;

    /** Motif du versement, saisi par l'opérateur. */
    @Column(columnDefinition = "text")
    private String observation;

    /** Version servie au téléchargement (une seule par document). */
    @Column(name = "is_default", nullable = false)
    private boolean principale = false;

    public DocumentVersion(UploadDocument document, String fileName, String extension, long sizeKo,
                           String observation, boolean principale) {
        this.document = document;
        this.fileName = fileName;
        this.extension = extension;
        this.sizeKo = sizeKo;
        this.observation = observation;
        this.principale = principale;
    }
}
