package com.ipt.ged.security;

import com.ipt.ged.common.IdentifiantUuid;
import com.ipt.ged.employe.Employe;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Compte de connexion d'un employé.
 *
 * <p>Séparé de {@link Employe} à dessein : un employé est une personne métier
 * (propriétaire de dossier, approbateur), et tous n'ont pas à pouvoir se
 * connecter. Mettre le mot de passe sur l'employé aurait obligé à créer un
 * secret pour des personnes qui n'ouvriront jamais l'application.
 *
 * <p>Le mot de passe n'est JAMAIS stocké en clair : le champ contient une
 * empreinte BCrypt, calculée par {@code PasswordEncoder}. Il n'est exposé par
 * aucun DTO ni aucun contrôleur.
 */
@Entity
@Table(name = "compte_utilisateur",
        uniqueConstraints = @UniqueConstraint(name = "uk_compte_utilisateur_email", columnNames = "email"))
@Getter
@Setter
@NoArgsConstructor
public class CompteUtilisateur {

    @Id
    @IdentifiantUuid
    private UUID id;

    /** Identifiant de connexion. Toujours rangé en minuscules — sans quoi
     *  « Sara@… » et « sara@… » désigneraient deux comptes différents. */
    @Column(nullable = false, unique = true, length = 190)
    private String email;

    /** Empreinte BCrypt. Le format porte son propre sel et son coût. */
    @Column(name = "mot_de_passe", nullable = false, length = 100)
    private String motDePasse;

    /**
     * Compte désactivé : il reste en base (l'historique des documents et des
     * signatures y renvoie) mais ne peut plus ouvrir de session. Supprimer le
     * compte casserait ces références.
     */
    @Column(nullable = false)
    private boolean actif = true;

    /** La personne métier derrière le compte : c'est son nom qui s'affiche. */
    @OneToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "employe_id", nullable = false, unique = true)
    private Employe employe;

    /** Dernière connexion réussie — utile pour repérer les comptes dormants. */
    @Column(name = "derniere_connexion")
    private Instant derniereConnexion;

    public CompteUtilisateur(String email, String empreinte, Employe employe) {
        this.email = email == null ? null : email.trim().toLowerCase();
        this.motDePasse = empreinte;
        this.employe = employe;
    }

    public void setEmail(String email) {
        this.email = email == null ? null : email.trim().toLowerCase();
    }
}
