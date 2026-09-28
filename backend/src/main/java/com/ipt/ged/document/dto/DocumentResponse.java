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
        boolean supprime,
        /** Chemin de rangement lisible : dossier / type. */
        String chemin,
        String createdBy,
        List<Tag> etiquettes,
        List<Version> versions,
        Instant createdAt,
        /** Niveau de confidentialité (§12.3). */
        String confidentialite,
        /**
         * Permissions de l'appelant sur ce document (fiche seulement ; absentes
         * des listes) : l'interface masque les actions correspondantes. Confort
         * seulement, le serveur revérifie chaque action.
         */
        List<String> permissions,
        /** Emplacements complémentaires visibles de l'appelant (§12.4 ; fiche seulement). */
        List<Ref> rattachements,
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
        String motifIndexation,
        /** {@code ACTIF} ou {@code ARCHIVE} (§12.6) : un document archivé est en lecture seule. */
        String statutConservation,
        Instant archiveLe,
        /** Socle commun (§12.7) : objet et date du document. */
        String objet,
        String dateDocument,
        /** Métadonnées du plan, normalisées, par code d'index. */
        java.util.Map<String, Object> metadonnees,
        /** Échéance de conservation (§12.9). */
        String echeanceConservation,
        /** Échéance atteinte (jour de MMED) : document à examiner par l'Agent d'archive, mis en évidence (§12.9). */
        boolean echeanceDepassee,
        /** Verrou (§12.8) : motif et date ; absents si le document est libre. */
        String verrouMotif,
        Instant verrouLe,
        /** Canal du dépôt (T-040) : INTERFACE, API, BUREAU_ORDRE, REPRISE. */
        String canalDepot,
        /** Application appelante (clé d'API), {@code null} depuis l'interface. */
        UUID applicationId,
        /** Identité GED du déposant ; déposant délégué si {@code depotDelegue}. */
        UUID deposantUtilisateurId,
        boolean depotDelegue
) {
    public record Ref(UUID id, String label) {}

    /** Étiquette avec sa couleur : la liste l'affiche en pastille. */
    public record Tag(UUID id, String tag, String couleur) {}

    /**
     * @param principale version COURANTE (une seule par document, §12.8)
     * @param typeMime   type réel détecté au dépôt
     * @param empreinte  SHA-256 du contenu en clair (§6.1.4)
     * @param numero     ordre de versement
     * @param auteurId   identité GED de l'auteur du versement
     */
    public record Version(UUID id, String fileName, String observation,
                          boolean principale, String sizeLabel, Instant createdAt,
                          String typeMime, String empreinte, int numero, UUID auteurId) {}

    public static DocumentResponse from(UploadDocument d) {
        return from(d, d.getWorkspace(), null, null, null);
    }

    public static DocumentResponse from(UploadDocument d, String statutOcr) {
        return from(d, d.getWorkspace(), null, null, statutOcr);
    }

    /**
     * @param emplacement   emplacement affiché : le principal s'il est accessible
     *                      à l'appelant, sinon son premier emplacement accessible (§12.4)
     * @param permissions   permissions de l'appelant ({@code null} dans les listes)
     * @param rattachements emplacements complémentaires visibles ({@code null} dans les listes)
     * @param statutOcr     état OCR de la version courante, {@code null} si sans objet
     */
    public static DocumentResponse from(UploadDocument d, com.ipt.ged.workspace.WorkSpace emplacement,
                                        List<String> permissions, List<Ref> rattachements, String statutOcr) {
        com.ipt.ged.workspace.WorkSpace ws = emplacement != null ? emplacement : d.getWorkspace();
        return new DocumentResponse(
                d.getId(), d.getName(),
                ws != null ? new Ref(ws.getId(), ws.getName()) : null,
                d.getTypeDocument() != null ? new Ref(d.getTypeDocument().getId(), d.getTypeDocument().getTypeDeDocument()) : null,
                d.getFileName(), d.getExtension(), d.getSizeKo(), humanSize(d.getSizeKo()),
                d.getExpirationDate() != null ? d.getExpirationDate().toString() : null,
                d.isActive(), d.isVerrouille(), d.isSupprime(),
                chemin(d, ws),
                d.getCreatedBy() != null ? d.getCreatedBy().getFullName() : null,
                tags(d), versions(d),
                d.getCreatedAt(),
                d.getConfidentialite() != null ? d.getConfidentialite().name() : null,
                permissions, rattachements,
                statutOcr,
                d.getStatutIndexation() != null ? d.getStatutIndexation().name() : null,
                null,
                d.getStatutConservation() != null ? d.getStatutConservation().name() : null,
                d.getArchiveLe(),
                d.getObjet(), d.getDateDocument() != null ? d.getDateDocument().toString() : null,
                d.getMetadonnees(),
                d.getEcheanceConservation() != null ? d.getEcheanceConservation().toString() : null,
                com.ipt.ged.document.conservation.Echeances.depassee(d.getEcheanceConservation()),
                d.getVerrouMotif(), d.getVerrouLe(),
                d.getCanalDepot() != null ? d.getCanalDepot().name() : null,
                d.getApplicationId(), d.getDeposantUtilisateurId(), d.isDepotDelegue());
    }

    /** Même réponse, avec l'issue d'indexation établie par le dépôt et son motif. */
    public DocumentResponse avecIndexation(String statut, String motif) {
        return new DocumentResponse(id, name, workspace, typeDocument, fileName, extension, sizeKo, sizeLabel,
                expirationDate, active, verrouille, supprime, chemin, createdBy, etiquettes, versions, createdAt,
                confidentialite, permissions, rattachements, statutOcr, statut, motif, statutConservation, archiveLe,
                objet, dateDocument, metadonnees, echeanceConservation, echeanceDepassee, verrouMotif, verrouLe,
                canalDepot, applicationId, deposantUtilisateurId, depotDelegue);
    }

    /**
     * Où le document est rangé, en une chaîne lisible. L'original affiche un
     * chemin de fichier ; le chemin disque de Marchica ne dirait rien à un
     * utilisateur, on montre donc le dossier et le type.
     */
    private static String chemin(UploadDocument d, com.ipt.ged.workspace.WorkSpace ws) {
        String dossier = ws != null ? ws.getName() : "—";
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
                            v.getTypeMime(), v.getEmpreinte(), v.getNumero(), v.getAuteurId()))
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
