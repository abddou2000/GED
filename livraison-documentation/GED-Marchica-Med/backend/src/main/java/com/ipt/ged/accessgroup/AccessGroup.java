package com.ipt.ged.accessgroup;

import com.ipt.ged.common.Auditable;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.workspace.WorkSpace;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Groupe d'accès : rattache des utilisateurs (employés) à des espaces de travail.
 * Il sert à l'organisation et à l'affichage (fiche d'espace, profil, tableau de
 * bord). Il ne conditionne aucune autorisation : l'application n'a qu'un seul
 * utilisateur, l'administrateur, et toute écriture lui est ouverte.
 */
@Entity
@Table(name = "access_groups")
@Getter
@Setter
@NoArgsConstructor
public class AccessGroup extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false, unique = true)
    private String name;

    /**
     * Colonnes héritées, conservées pour ne rien détruire en base (le mapping les
     * garde donc {@code ddl-auto: update} ne les touche pas). Plus jamais lues ni
     * écrites par l'application.
     */
    @Embedded
    private GedRights rights = new GedRights();

    /** Espaces de travail couverts par le groupe (N–N). */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "pivot_workspace_groups",
            joinColumns = @JoinColumn(name = "access_group_id"),
            inverseJoinColumns = @JoinColumn(name = "workspace_id"))
    private Set<WorkSpace> workspaces = new LinkedHashSet<>();

    /** Membres du groupe (employés) — relation « users » de CCISTTA (N–N). */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "pivot_employe_groups",
            joinColumns = @JoinColumn(name = "access_group_id"),
            inverseJoinColumns = @JoinColumn(name = "employe_id"))
    private Set<Employe> users = new LinkedHashSet<>();

    /** Corbeille : true = supprimé de façon réversible. */
    @Column(nullable = false)
    private boolean deleted = false;

    public AccessGroup(String code, String name) {
        this.code = code;
        this.name = name;
    }
}
