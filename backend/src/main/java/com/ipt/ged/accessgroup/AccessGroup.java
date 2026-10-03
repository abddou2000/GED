package com.ipt.ged.accessgroup;

import com.ipt.ged.common.IdentifiantUuid;
import com.ipt.ged.common.Supprimable;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.identite.Utilisateur;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Groupe interne à la GED (dossier technique §12.2.1, table {@code groupe_ged}) :
 * un ensemble de personnes, sujet d'habilitations comme un utilisateur. Son
 * appartenance est gérée dans la GED, jamais déduite de l'annuaire (P2).
 *
 * <p>Les espaces qu'un groupe « couvre » ne sont plus une table à part : ce sont
 * ses habilitations sur des nœuds (voir {@code autorisation.Habilitation}).
 */
@Entity
@Table(name = "groupe_ged")
@Getter
@Setter
@NoArgsConstructor
public class AccessGroup extends Supprimable {

    @Id
    @IdentifiantUuid
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    /** Colonne {@code nom} (T-025, §4.2.2) ; propriété {@code name} de l'API inchangée. */
    @Column(name = "nom", nullable = false, unique = true)
    private String name;

    /**
     * Membres du groupe (table {@code groupe_membre}) : des identités GED
     * (§12.1, §12.2.1 ; T-025, décision du client du 03/10).
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "groupe_membre",
            joinColumns = @JoinColumn(name = "groupe_ged_id"),
            inverseJoinColumns = @JoinColumn(name = "utilisateur_id"))
    private Set<Utilisateur> membres = new LinkedHashSet<>();

    /**
     * Appartenances préparées pour des personnes qui n'ont pas encore d'identité
     * GED (table {@code groupe_membre_attente}) : membres repris de l'ancienne
     * application, ou désignés avant leur première connexion. Elles n'apportent
     * aucun droit et deviennent des {@link #membres} à la première connexion
     * ({@code ServiceIdentites}), sans action de l'Administrateur.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "groupe_membre_attente",
            joinColumns = @JoinColumn(name = "groupe_ged_id"),
            inverseJoinColumns = @JoinColumn(name = "employe_id"))
    private Set<Employe> membresEnAttente = new LinkedHashSet<>();

    public AccessGroup(String code, String name) {
        this.code = code;
        this.name = name;
    }
}
