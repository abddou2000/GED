package com.ipt.ged.cycledevie;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Point d'extension sur les dossiers, utilisé par l'archivage d'un dossier
 * entier (§12.6, revue client D10) et par l'export de dossier (§12.10).
 *
 * <p>Le référentiel des dossiers change de forme au lot E3 (nœuds à chemin
 * matérialisé, rattachements multiples, drapeau d'archivage sur le nœud, dev1) :
 * le cycle de vie ne lit ni n'écrit ces tables directement. L'implémentation
 * livrée ici ({@link DossiersEspaces}) s'appuie sur les espaces de travail
 * actuels ; le lot E3 la remplace en déclarant son propre bean de ce type.
 */
public interface Dossiers {

    /** Un dossier, et son drapeau d'archivage propre. */
    record Dossier(UUID id, String nom, boolean archive) {
    }

    /**
     * Un document rangé sous le dossier.
     *
     * @param chemins chemins du document relatifs au dossier exporté (« » à la
     *                racine, « Sous-dossier/Autre » plus bas) : le premier est
     *                l'emplacement principal, les suivants ses rattachements
     *                situés dans la même arborescence (§12.4).
     */
    record DocumentRange(UUID documentId, List<String> chemins) {
    }

    Optional<Dossier> trouver(UUID dossierId);

    /** Vrai si le dossier ou l'un de ses ancêtres porte le drapeau d'archivage (dépôt refusé, Q7). */
    boolean estArchive(UUID dossierId);

    /** Pose ou retire le drapeau d'archivage du dossier (D10). */
    void marquerArchive(UUID dossierId, boolean archive);

    /**
     * Documents hors corbeille du dossier et de toute sa sous-arborescence,
     * chacun <b>une seule fois</b>, dans un ordre stable.
     */
    List<DocumentRange> documents(UUID dossierId);
}
