package com.ipt.ged.identite;

import com.ipt.ged.common.IdentifiantUuid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Ligne de la table technique {@code cache_annuaire} (dossier technique §3.4.2) :
 * attributs lus dans l'annuaire, en lecture seule, expirant après 15 minutes.
 * Ce n'est pas une source de vérité : rien ne s'y saisit dans la GED.
 */
@Entity
@Table(name = "cache_annuaire")
@Getter
@Setter
@NoArgsConstructor
public class EntreeCacheAnnuaire {

    @Id
    @IdentifiantUuid
    private UUID id;

    @Column(name = "utilisateur_id", nullable = false, unique = true)
    private UUID utilisateurId;

    @Column(nullable = false, length = 64)
    private String identifiant;

    private String prenom;
    private String nom;

    @Column(name = "nom_affiche")
    private String nomAffiche;

    private String courriel;

    /** Attribut {@code department} (direction), s'il est renseigné. */
    private String direction;

    @Column(name = "lu_le", nullable = false)
    private Instant luLe;

    @Column(name = "expire_le", nullable = false)
    private Instant expireLe;

    public boolean estExpiree(Instant maintenant) {
        return !maintenant.isBefore(expireLe);
    }
}
