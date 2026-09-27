package com.ipt.ged.accessgroup;

import com.ipt.ged.common.IdentifiantUuid;
import com.ipt.ged.common.Supprimable;
import com.ipt.ged.employe.Employe;
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

    @Column(nullable = false, unique = true)
    private String name;

    /**
     * Colonnes héritées, conservées pour ne rien perdre des données reprises de
     * l'ancienne base (le schéma Liquibase les porte, le mapping doit donc les
     * déclarer pour que {@code ddl-auto: validate} passe). Plus jamais lues ni
     * écrites par l'application.
     */
    @Embedded
    private GedRights rights = new GedRights();

    /**
     * Membres du groupe (table {@code groupe_membre}). Le membre est la personne
     * (employé), à laquelle l'identité d'annuaire est rattachée une à une.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "groupe_membre",
            joinColumns = @JoinColumn(name = "groupe_ged_id"),
            inverseJoinColumns = @JoinColumn(name = "employe_id"))
    private Set<Employe> users = new LinkedHashSet<>();


    public AccessGroup(String code, String name) {
        this.code = code;
        this.name = name;
    }
}
