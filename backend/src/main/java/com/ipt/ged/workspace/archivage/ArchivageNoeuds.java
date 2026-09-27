package com.ipt.ged.workspace.archivage;

import com.ipt.ged.common.StatutConservation;

import java.util.List;
import java.util.UUID;

/**
 * <b>Contrat pour le lot cycle de vie (dev3) : archivage d'un dossier entier</b>
 * (dossier technique §12.6, décision D10 : « drapeau sur documents ou
 * dossiers », archivage manuel d'un dossier en une fois).
 *
 * <p>Le nœud et son chemin matérialisé appartiennent au lot modèle (dev1) ; le
 * traitement de fond ({@code job_archivage}, tranches de 100 documents, PDF/A,
 * empreinte, événement d'audit par document) appartient au lot cycle de vie.
 * Enchaînement attendu du job :
 * <ol>
 *   <li>l'appelant vérifie la permission Archiver sur le nœud
 *       ({@code ControleAcces.exigerSurNoeud(ARCHIVER, noeudId)}) ;</li>
 *   <li>{@link #documentsAArchiver} par tranches (pagination par clé), chaque
 *       document archivé par {@code document.archivage.ArchivageDocuments} dans
 *       sa propre transaction ;</li>
 *   <li>{@link #marquerArchive} une fois toutes les tranches passées : le
 *       drapeau du nœud dit « dossier archivé en entier ».</li>
 * </ol>
 * Aucune méthode ne contrôle les droits : c'est au service appelant de le
 * faire, avant.
 */
public interface ArchivageNoeuds {

    /** Statut de conservation d'un nœud ; 404 ({@code EntityNotFoundException}) s'il n'existe pas. */
    StatutConservation statut(UUID noeudId);

    /**
     * Pose le drapeau ARCHIVE sur le nœud et toute sa sous-arborescence, même
     * auteur et même horodatage (idempotent : un nœud déjà archivé garde sa
     * date d'origine).
     *
     * @param auteurUtilisateurId identité GED de l'archiviste ({@code utilisateur.id})
     * @return nombre de nœuds nouvellement archivés
     */
    int marquerArchive(UUID noeudId, UUID auteurUtilisateurId);

    /**
     * Désarchivage : lève le drapeau sur le nœud et sa sous-arborescence. Les
     * documents se désarchivent un par un (ils peuvent avoir été archivés
     * indépendamment du dossier).
     *
     * @return nombre de nœuds désarchivés
     */
    int marquerActif(UUID noeudId);

    /**
     * Documents à archiver d'un dossier : vivants (hors corbeille), non encore
     * archivés, dont l'emplacement PRINCIPAL est le nœud ou l'un de ses
     * descendants. Les documents seulement rattachés au dossier n'en font pas
     * partie : leur statut est porté par le document et s'applique à tous ses
     * emplacements (§12.6), il se décide depuis leur emplacement principal.
     *
     * @param apres  dernier identifiant de la tranche précédente ({@code null} pour la première)
     * @param taille taille de la tranche (100 au §12.6)
     * @return identifiants croissants, au plus {@code taille}
     */
    List<UUID> documentsAArchiver(UUID noeudId, UUID apres, int taille);
}
