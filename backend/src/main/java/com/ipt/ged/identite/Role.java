package com.ipt.ged.identite;

import com.ipt.ged.autorisation.CodePermission;
import com.ipt.ged.autorisation.Permission;
import com.ipt.ged.common.Auditable;
import com.ipt.ged.common.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Rôle GED (dossier technique §12.2.1) : un ensemble nommé de permissions,
 * composé depuis l'interface. Les quatre rôles système sont livrés par le
 * changeset {@code data-initial} 202609271000-2, leur composition par
 * 202609281015-2, corrigée par 202610041000 (Agent d'archive, ANO-F-001) et
 * 202610071000 (Direction Générale sans Déplacer, Archiver ni Supprimer,
 * ANO-F-002) ; leurs codes sont stables.
 */
@Entity
@Table(name = "role")
@Getter
@NoArgsConstructor
public class Role extends Auditable {

    public static final String ADMINISTRATEUR = "ADMINISTRATEUR";
    public static final String AGENT_ARCHIVE = "AGENT_ARCHIVE";
    public static final String DIRECTION_GENERALE = "DIRECTION_GENERALE";
    public static final String UTILISATEUR_STANDARD = "UTILISATEUR_STANDARD";

    /** Clé fixe, livrée par changeset : jamais générée. */
    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Setter
    @Column(nullable = false)
    private String libelle;

    @Column(nullable = false)
    private boolean systeme;

    /**
     * Direction Générale : les permissions élémentaires de sa composition valent
     * sur tout nœud, traitées par le code (E3) — consulter, déposer, modifier,
     * valider, diffuser ; aucune permission de structuration (ANO-F-002).
     */
    @Column(name = "acces_global", nullable = false)
    private boolean accesGlobal;

    /** Composition du rôle (table {@code role_permission}). */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "role_permission",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new LinkedHashSet<>();

    /** Rôle créé depuis l'interface : jamais système, jamais à accès global. */
    public Role(String code, String libelle) {
        this.id = UuidV7.suivant();
        this.code = Objects.requireNonNull(code);
        this.libelle = Objects.requireNonNull(libelle);
    }

    /** Permissions connues du code ; une ligne inconnue est ignorée. */
    public Set<CodePermission> codesPermissions() {
        EnumSet<CodePermission> s = EnumSet.noneOf(CodePermission.class);
        for (Permission p : permissions) {
            CodePermission c = p.codePermission();
            if (c != null) s.add(c);
        }
        return s;
    }
}
