package com.ipt.ged.workspace;

import com.ipt.ged.accessgroup.AccessGroup;
import com.ipt.ged.common.Auditable;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.workflow.WorkflowGed;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Espace de travail (dossier) — organisé en arborescence auto-référencée.
 * Porte un propriétaire, un statut et un circuit de validation (workflow).
 */
@Entity
@Table(name = "work_spaces")
@Getter
@Setter
@NoArgsConstructor
public class WorkSpace extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String code;

    @Column
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkspaceStatus status = WorkspaceStatus.ACTIF;

    /** Propriétaire (responsable) du dossier. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "employe_id", nullable = false)
    private Employe owner;

    /** Dossier parent (null = racine) — auto-référence pour l'arborescence. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_workspace_id")
    private WorkSpace parent;

    /** Circuit de validation appliqué aux documents du dossier. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "workflow_ged_id", nullable = false)
    private WorkflowGed workflow;

    @OneToMany(mappedBy = "parent")
    @OrderBy("id ASC")
    private List<WorkSpace> children = new ArrayList<>();

    /**
     * Groupes d'accès couvrant ce dossier — côté inverse de la table pivot déjà
     * portée par {@code AccessGroup}. Sans cette relation, on ne pouvait répondre
     * à « qui a accès à ce dossier ? » qu'en parcourant tous les groupes.
     */
    @ManyToMany(mappedBy = "workspaces", fetch = FetchType.LAZY)
    private Set<AccessGroup> accessGroups = new LinkedHashSet<>();

    /** Corbeille : true = supprimé de façon réversible. */
    @Column(nullable = false)
    private boolean deleted = false;

    public WorkSpace(String name, String code) {
        this.name = name;
        this.code = code;
    }

    /** Tous les identifiants de la descendance (récursif), pour l'anti-cycle du déplacement. */
    public List<Long> getAllChildrenIds() {
        List<Long> ids = new ArrayList<>();
        for (WorkSpace child : children) {
            ids.add(child.getId());
            ids.addAll(child.getAllChildrenIds());
        }
        return ids;
    }
}
