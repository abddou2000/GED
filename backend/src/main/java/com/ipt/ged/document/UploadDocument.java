package com.ipt.ged.document;

import com.ipt.ged.common.IdentifiantUuid;
import com.ipt.ged.common.Supprimable;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.etiquette.Etiquette;
import com.ipt.ged.typedocument.TypeDocument;
import com.ipt.ged.workspace.WorkSpace;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Document déposé dans la GED : fiche, fichier stocké sur disque, étiquettes,
 * versions et valeurs d'index. Reproduit {@code UploadDocument} de CCISTTA.
 */
@Entity
@Table(name = "document")
@Getter
@Setter
@NoArgsConstructor
public class UploadDocument extends Supprimable {

    @Id
    @IdentifiantUuid
    private UUID id;

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

    /**
     * Verrouillé : plus aucune modification ni nouvelle version tant que le
     * verrou tient. Reprend {@code is_locked} de l'original.
     */
    // Valeur par défaut au niveau SQL : sans elle, ajouter cette colonne à une
    // table déjà peuplée échoue (« NULL non permis »), et le schéma reste à moitié
    // migré sans que rien ne le signale au démarrage.
    @Column(name = "is_locked", nullable = false, columnDefinition = "boolean default false")
    private boolean verrouille = false;

    /** Étiquettes apposées au document (N–N). */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "document_etiquette",
            joinColumns = @JoinColumn(name = "document_id"),
            inverseJoinColumns = @JoinColumn(name = "etiquette_id"))
    private Set<Etiquette> etiquettes = new LinkedHashSet<>();

    /**
     * Auteur du dépôt. Renseigné depuis le frontend faute d'authentification
     * serveur : la colonne existe pour que la liste puisse afficher « Créateur »
     * comme l'original, sans prétendre à une traçabilité vérifiée.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "created_by_employe_id")
    private Employe createdBy;

    /** Versions successives du fichier, la plus récente d'abord. */
    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id DESC")
    private List<DocumentVersion> versions = new ArrayList<>();

    /**
     * Métadonnées additionnelles, en JSONB indexé GIN (dossier technique §12.7).
     *
     * <p>Préparation du lot E7 : aucune fonction ne l'alimente encore. Jamais
     * {@code null} — un document sans métadonnée porte un objet vide, comme le
     * garantit la colonne ({@code NOT NULL DEFAULT '{}'}).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadonnees", nullable = false)
    private Map<String, Object> metadonnees = new HashMap<>();

    public UploadDocument(String name) {
        this.name = name;
    }
}
