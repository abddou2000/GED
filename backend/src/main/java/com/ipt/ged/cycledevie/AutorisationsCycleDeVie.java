package com.ipt.ged.cycledevie;

import java.util.UUID;

/**
 * Point d'extension des permissions du cycle de vie (§12.5, §12.6), fourni par
 * le lot autorisation (E3, dev1) : {@code Purger}, {@code Archiver}
 * (Agent d'archive et Administrateur), désarchivage réservé aux mêmes profils.
 *
 * <p>L'implémentation livrée ici ({@link AutorisationsCycleDeVieProvisoires})
 * n'accorde ces opérations qu'à un utilisateur authentifié ; le lot E3 la
 * remplace en déclarant son propre bean de ce type.
 */
public interface AutorisationsCycleDeVie {

    /** Permission {@code Purger} sur le document (§12.5). */
    boolean peutPurger(UUID documentId);

    /** Permission {@code Archiver} sur le document (§12.6). */
    boolean peutArchiver(UUID documentId);

    /** Permission {@code Archiver} sur le dossier : archivage d'un dossier entier (D10). */
    boolean peutArchiverDossier(UUID dossierId);

    /** Désarchivage : réservé à l'Agent d'archive et à l'Administrateur (§12.6). */
    boolean peutDesarchiver(UUID documentId);
}
