package com.ipt.ged.common;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Entité à corbeille : suppression douce avec auteur et date (dossier
 * technique §12.1, §12.5). Seule la purge efface ; la mise en corbeille est
 * réversible.
 *
 * <p>Les trois colonnes ne se modifient qu'ensemble, par
 * {@link #mettreEnCorbeille} et {@link #restaurer} : il n'y a volontairement
 * pas de {@code setSupprime}. Un indicateur remis à faux en laissant l'auteur et
 * la date en place décrirait un objet vivant « supprimé par X » — la contrainte
 * {@code ck_<table>_suppression} le refuserait d'ailleurs en base.
 */
@MappedSuperclass
@Getter
public abstract class Supprimable extends Auditable {

    /** Corbeille : true = supprimé de façon réversible. */
    @Column(nullable = false)
    private boolean supprime = false;

    /** Employé qui a mis l'objet en corbeille ; null s'il est vivant. */
    @Column(name = "supprime_par")
    private UUID supprimePar;

    /** Instant de la mise en corbeille ; null s'il est vivant. */
    @Column(name = "supprime_le")
    private Instant supprimeLe;

    /**
     * Met l'objet en corbeille.
     *
     * @param auteur employé à l'origine de la suppression ; {@code null} pour un
     *               traitement technique sans acteur authentifié.
     */
    public void mettreEnCorbeille(UUID auteur) {
        // Tronqué à la microseconde, précision de la colonne : deux objets
        // supprimés ensemble gardent le même horodatage une fois relus.
        mettreEnCorbeille(auteur, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    /**
     * Mise en corbeille à un instant donné : une suppression en cascade (nœud
     * et sous-arborescence) date tous les objets emportés du même instant, ce
     * qui permet de ne restaurer que ceux-là.
     */
    public void mettreEnCorbeille(UUID auteur, Instant le) {
        this.supprime = true;
        this.supprimePar = auteur;
        this.supprimeLe = le;
    }

    /** Sort l'objet de la corbeille : l'auteur et la date de suppression sont effacés. */
    public void restaurer() {
        this.supprime = false;
        this.supprimePar = null;
        this.supprimeLe = null;
    }
}
