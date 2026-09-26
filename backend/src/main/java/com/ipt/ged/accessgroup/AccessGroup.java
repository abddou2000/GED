package com.ipt.ged.accessgroup;

import com.ipt.ged.common.IdentifiantUuid;
import com.ipt.ged.common.Supprimable;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.workspace.WorkSpace;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Groupe d'accès : rattache des utilisateurs (employés) à des espaces de travail.
 * Il sert à l'organisation et à l'affichage (fiche d'espace, profil, tableau de
 * bord). Il ne conditionne aucune autorisation : l'application n'a qu'un seul
 * utilisateur, l'administrateur, et toute écriture lui est ouverte.
 */
@Entity
@Table(name = "access_group")
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

    /** Espaces de travail couverts par le groupe (N–N). */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "access_group_workspace",
            joinColumns = @JoinColumn(name = "access_group_id"),
            inverseJoinColumns = @JoinColumn(name = "workspace_id"))
    private Set<WorkSpace> workspaces = new LinkedHashSet<>();

    /** Membres du groupe (employés) — relation « users » de CCISTTA (N–N). */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "access_group_employe",
            joinColumns = @JoinColumn(name = "access_group_id"),
            inverseJoinColumns = @JoinColumn(name = "employe_id"))
    private Set<Employe> users = new LinkedHashSet<>();


    public AccessGroup(String code, String name) {
        this.code = code;
        this.name = name;
    }
}
