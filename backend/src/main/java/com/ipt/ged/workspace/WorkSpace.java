package com.ipt.ged.workspace;

import com.ipt.ged.common.IdentifiantUuid;
import com.ipt.ged.common.Supprimable;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.workflow.WorkflowGed;
import jakarta.persistence.*;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Nœud de l'organisation documentaire (dossier technique §12.1) : un ESPACE
 * (racine) ou un DOSSIER, organisé en arborescence auto-référencée. Porte un
 * propriétaire, un statut et un circuit de validation (workflow).
 *
 * <p>La classe garde son nom historique ({@code WorkSpace}) et l'API ses
 * chemins {@code /workspaces} : seule la table change ({@code noeud}), pour ne
 * pas imposer un renommage à toute l'interface pendant le lot E3.
 *
 * <p>Le {@link #getChemin() chemin matérialisé} est tenu par la base
 * (déclencheurs du changeset 202609281000-3) : l'application ne l'écrit jamais,
 * elle le relit après chaque insertion ou mise à jour.
 */
@Entity
@Table(name = "noeud")
@Getter
@Setter
@NoArgsConstructor
public class WorkSpace extends Supprimable {

    @Id
    @IdentifiantUuid
    private UUID id;

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
    @JoinColumn(name = "parent_id")
    private WorkSpace parent;

    /** Circuit de validation appliqué aux documents du dossier. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "workflow_ged_id", nullable = false)
    private WorkflowGed workflow;

    @OneToMany(mappedBy = "parent")
    @OrderBy("id ASC")
    private List<WorkSpace> children = new ArrayList<>();

    /**
     * Chemin matérialisé « /id-espace/…/id/ » : la sous-arborescence d'un nœud
     * est l'ensemble des nœuds dont le chemin commence par le sien.
     */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "chemin", insertable = false, updatable = false)
    private String chemin;

    /** ESPACE (racine) ou DOSSIER, déduit du parent par la base. */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "nature", insertable = false, updatable = false)
    private String nature;


    public WorkSpace(String name, String code) {
        this.name = name;
        this.code = code;
    }

    /** Tous les identifiants de la descendance (récursif), pour l'anti-cycle du déplacement. */
    public List<UUID> getAllChildrenIds() {
        List<UUID> ids = new ArrayList<>();
        for (WorkSpace child : children) {
            ids.add(child.getId());
            ids.addAll(child.getAllChildrenIds());
        }
        return ids;
    }
}
