package com.ipt.ged.identite;

import com.ipt.ged.common.Auditable;
import com.ipt.ged.common.IdentifiantUuid;
import com.ipt.ged.employe.Employe;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Identité GED d'une personne de l'annuaire (dossier technique §3.2, §3.3).
 *
 * <p>Ne porte que la clé technique d'annuaire ({@code objectGUID}, immuable) et
 * l'identifiant de connexion ({@code sAMAccountName}, tenu à jour à chaque
 * connexion : un changement de login ne crée pas de doublon). <b>Aucun mot de
 * passe</b> : la vérification reste déléguée à l'annuaire.
 *
 * <p>L'identité est rattachée à la personne métier ({@link Employe}) à laquelle
 * renvoient déjà dossiers, dépôts et circuits de validation.
 */
@Entity
@Table(name = "utilisateur")
@Getter
@Setter
@NoArgsConstructor
public class Utilisateur extends Auditable {

    @Id
    @IdentifiantUuid
    private UUID id;

    /** objectGUID de l'annuaire, sous sa forme usuelle (ordre d'octets Microsoft). */
    @Column(name = "object_guid", nullable = false, unique = true)
    private UUID objectGuid;

    /** sAMAccountName, tel que l'annuaire l'écrit. */
    @Column(nullable = false, length = 64)
    private String identifiant;

    @OneToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "employe_id", nullable = false, unique = true)
    private Employe employe;

    @Column(name = "derniere_connexion_le")
    private Instant derniereConnexionLe;

    /**
     * Rôles attribués par l'Administrateur. Vide à la création : une identité
     * provisionnée n'a aucun droit (§3.2). Jamais déduits de l'annuaire (P2).
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "utilisateur_role",
            joinColumns = @JoinColumn(name = "utilisateur_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new LinkedHashSet<>();

    public Utilisateur(UUID objectGuid, String identifiant, Employe employe) {
        this.objectGuid = objectGuid;
        this.identifiant = identifiant;
        this.employe = employe;
    }
}
