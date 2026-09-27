package com.ipt.ged.document.dto;

import com.ipt.ged.document.UploadDocument;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Données renvoyées au frontend pour un document déposé.
 */
public record DocumentResponse(
        UUID id,
        String name,
        Ref workspace,
        Ref typeDocument,
        String fileName,
        String extension,
        long sizeKo,
        String sizeLabel,
        String expirationDate,
        boolean active,
        boolean verrouille,
        /**
         * Document en corbeille. Absent de la réponse jusqu'ici : la fiche d'un
         * document supprimé s'affichait donc à l'identique d'un document vivant,
         * l'écran n'ayant aucun moyen de savoir que toute écriture y sera refusée.
         */
        boolean deleted,
        /** Chemin de rangement lisible : dossier / type. */
        String chemin,
        String createdBy,
        List<Tag> etiquettes,
        List<Version> versions,
        Instant createdAt,
        /**
         * État du traitement OCR de la version courante (§4.3.4) :
         * {@code EN_ATTENTE_OCR}, {@code EN_COURS_OCR}, {@code OCR_TERMINE} (document
         * interrogeable) ou {@code OCR_ECHEC} (« contenu non interrogeable ») ;
         * {@code null} si la version n'a pas de contenu textuel à extraire.
         */
        String statutOcr,
        /**
         * Issue de l'indexation (§12.11) : {@code INDEXE}, {@code SANS_PLAN} ou
         * {@code A_INDEXER} (métadonnées à saisir ou à reprendre).
         */
        String statutIndexation,
        /** Pourquoi l'indexation du dépôt n'a pas abouti ; renseigné par le seul dépôt. */
        String motifIndexation
) {
    public record Ref(UUID id, String label) {}

    /** Étiquette avec sa couleur : la liste l'affiche en pastille. */
    public record Tag(UUID id, String tag, String couleur) {}

    /**
     * @param typeMime  type réel détecté au dépôt ;
     * @param empreinte SHA-256 du contenu en clair (§6.1.4).
     */
    public record Version(UUID id, String fileName, String observation,
                          boolean principale, String sizeLabel, Instant createdAt,
                          String typeMime, String empreinte) {}

    public static DocumentResponse from(UploadDocument d) {
        return from(d, null);
    }

    public static DocumentResponse from(UploadDocument d, String statutOcr) {
        return new DocumentResponse(
                d.getId(), d.getName(),
                d.getWorkspace() != null ? new Ref(d.getWorkspace().getId(), d.getWorkspace().getName()) : null,
                d.getTypeDocument() != null ? new Ref(d.getTypeDocument().getId(), d.getTypeDocument().getTypeDeDocument()) : null,
                d.getFileName(), d.getExtension(), d.getSizeKo(), humanSize(d.getSizeKo()),
                d.getExpirationDate() != null ? d.getExpirationDate().toString() : null,
                d.isActive(), d.isVerrouille(), d.isDeleted(),
                chemin(d),
                d.getCreatedBy() != null ? d.getCreatedBy().getFullName() : null,
                tags(d), versions(d),
                d.getCreatedAt(),
                statutOcr,
                d.getStatutIndexation() != null ? d.getStatutIndexation().name() : null,
                null);
    }

    /** Même réponse, avec l'issue d'indexation établie par le dépôt et son motif. */
    public DocumentResponse avecIndexation(String statut, String motif) {
        return new DocumentResponse(id, name, workspace, typeDocument, fileName, extension, sizeKo, sizeLabel,
                expirationDate, active, verrouille, deleted, chemin, createdBy, etiquettes, versions, createdAt,
                statutOcr, statut, motif);
    }

    /**
     * Où le document est rangé, en une chaîne lisible. L'original affiche un
     * chemin de fichier ; le chemin disque de Marchica ne dirait rien à un
     * utilisateur, on montre donc le dossier et le type.
     */
    private static String chemin(UploadDocument d) {
        String dossier = d.getWorkspace() != null ? d.getWorkspace().getName() : "—";
        String type = d.getTypeDocument() != null ? d.getTypeDocument().getTypeDeDocument() : null;
        return type != null ? dossier + " / " + type : dossier;
    }

    private static List<Tag> tags(UploadDocument d) {
        try {
            return d.getEtiquettes().stream()
                    .map(e -> new Tag(e.getId(), e.getTag(), e.getCouleur()))
                    .toList();
        } catch (RuntimeException e) {
            // Collection non initialisée hors transaction : une liste vide vaut
            // mieux qu'une erreur qui priverait la page entière de réponse.
            return List.of();
        }
    }

    private static List<Version> versions(UploadDocument d) {
        try {
            return d.getVersions().stream()
                    .map(v -> new Version(v.getId(), v.getFileName(), v.getObservation(),
                            v.isPrincipale(), humanSize(v.getSizeKo()), v.getCreatedAt(),
                            v.getTypeMime(), v.getEmpreinte()))
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static String humanSize(long ko) {
        if (ko < 1024) return ko + " Ko";
        return String.format("%.1f Mo", ko / 1024.0);
    }
}
