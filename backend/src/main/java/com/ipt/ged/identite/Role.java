package com.ipt.ged.identite;

import com.ipt.ged.common.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Rôle GED (dossier technique §12.2.1). Les quatre rôles système sont livrés par
 * le changeset {@code data-initial} 202609271000-2 ; leurs codes sont stables.
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

    @Column(nullable = false)
    private String libelle;

    @Column(nullable = false)
    private boolean systeme;

    /** Direction Générale : lecture et écriture sur tout nœud, traitées par le code (E3). */
    @Column(name = "acces_global", nullable = false)
    private boolean accesGlobal;
}
