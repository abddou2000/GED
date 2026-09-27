package com.ipt.ged.document;

import com.ipt.ged.autorisation.Confidentialite;
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

    /**
     * Emplacement PRINCIPAL du document (§12.4, {@code noeud_principal_id}),
     * déterminé par le type documentaire au dépôt. Les emplacements
     * complémentaires sont dans {@code document_rattachement}.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "noeud_principal_id", nullable = false)
    private WorkSpace workspace;

    /**
     * Statut de conservation (§12.6) : porté par le document, donc identique
     * depuis tous ses emplacements. ARCHIVE = lecture seule
     * ({@link GardeEcriture}).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "statut_conservation", nullable = false, length = 10)
    private com.ipt.ged.common.StatutConservation statutConservation = com.ipt.ged.common.StatutConservation.ACTIF;

    @Column(name = "archive_le")
    private java.time.Instant archiveLe;

    /** Identité GED de l'archiviste. */
    @Column(name = "archive_par")
    private UUID archivePar;

    /**
     * Échéance de conservation (§12.9) : calculée PAR LA BASE à partir du type
     * (durée, point de départ) ; jamais écrite par l'application.
     */
    @org.hibernate.annotations.Generated(event = {org.hibernate.generator.EventType.INSERT,
            org.hibernate.generator.EventType.UPDATE})
    @Column(name = "echeance_conservation", insertable = false, updatable = false)
    private LocalDate echeanceConservation;

    /**
     * Version du plan d'indexation en vigueur au dépôt (§12.7) : les
     * métadonnées du document sont validées contre ELLE, pas contre le plan
     * courant du type.
     */
    @Column(name = "plan_indexation_version_id")
    private UUID planIndexationVersionId;

    /**
     * Niveau de confidentialité (§12.3), indépendant de l'emplacement ; fixé au
     * dépôt d'après le type documentaire.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "confidentialite", nullable = false)
    private Confidentialite confidentialite = Confidentialite.PUBLIC;

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

    /** Auteur (identité GED), date et motif du verrou (§12.8). */
    @Column(name = "verrou_par")
    private UUID verrouPar;

    @Column(name = "verrou_le")
    private java.time.Instant verrouLe;

    @Column(name = "verrou_motif", length = 500)
    private String verrouMotif;

    /** Objet du document (socle commun, §12.7). */
    @Column(name = "objet", length = 1000)
    private String objet;

    /**
     * Date du document (socle commun, §12.7) : clé de tri prioritaire de la
     * recherche et point de départ par défaut de la conservation. Date de dépôt
     * quand elle n'est pas fournie.
     */
    @Column(name = "date_document", nullable = false)
    private LocalDate dateDocument = LocalDate.now();

    /** Pose le verrou : auteur, date et motif (§12.8). */
    public void verrouiller(UUID auteur, String motif) {
        this.verrouille = true;
        this.verrouPar = auteur;
        this.verrouLe = java.time.Instant.now();
        this.verrouMotif = motif;
    }

    /** Lève le verrou. */
    public void deverrouiller() {
        this.verrouille = false;
        this.verrouPar = null;
        this.verrouLe = null;
        this.verrouMotif = null;
    }

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
