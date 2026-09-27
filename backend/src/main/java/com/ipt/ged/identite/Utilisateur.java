package com.ipt.ged.identite;

import com.ipt.ged.common.Auditable;
import com.ipt.ged.common.IdentifiantUuid;
import com.ipt.ged.employe.Employe;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
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
 *
 * <p>Ses rôles ne sont pas portés ici : ce sont des habilitations (lot E3,
 * §12.2.1), globales ou sur un nœud, directes ou par ses groupes GED.
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

    public Utilisateur(UUID objectGuid, String identifiant, Employe employe) {
        this.objectGuid = objectGuid;
        this.identifiant = identifiant;
        this.employe = employe;
    }
}
