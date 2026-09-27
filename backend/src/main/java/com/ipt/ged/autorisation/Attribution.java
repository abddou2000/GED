package com.ipt.ged.autorisation;

import com.ipt.ged.identite.Role;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Attribution applicable à un sujet, telle que le résolveur la consomme : la
 * forme commune aux habilitations de la table {@code habilitation} et à toute
 * autre {@link SourceHabilitations} (portée d'une clé d'API, lot E9).
 *
 * @param origineId   identifiant de la ligne d'origine (habilitation, portée de clé…)
 * @param viaType     porteur : l'utilisateur lui-même, un de ses groupes, l'application
 * @param viaId       identifiant du porteur
 * @param viaLibelle  libellé du porteur (nom du groupe…), pour l'explication des droits
 * @param roleId      rôle attribué ({@code null} pour une rupture seule ou une portée sans rôle)
 * @param roleCode    code du rôle
 * @param accesGlobal rôle à accès global (Direction Générale, §12.2.2)
 * @param permissions permissions apportées
 * @param noeudId     nœud cible ; avec {@code documentId} {@code null} = portée globale
 * @param documentId  document cible (« document isolé »)
 * @param rupture     rupture d'héritage sur le nœud
 */
public record Attribution(UUID origineId, TypeSujet viaType, UUID viaId, String viaLibelle,
                          UUID roleId, String roleCode, boolean accesGlobal, Set<CodePermission> permissions,
                          UUID noeudId, UUID documentId, boolean rupture) {

    public Attribution {
        permissions = permissions == null || permissions.isEmpty()
                ? EnumSet.noneOf(CodePermission.class) : EnumSet.copyOf(permissions);
    }

    public boolean globale() {
        return noeudId == null && documentId == null;
    }

    /** Traduction d'une ligne {@code habilitation}. */
    public static Attribution depuis(Habilitation h, String viaLibelle) {
        Role r = h.getRole();
        return new Attribution(h.getId(), h.getSujetType(), h.sujetId(), viaLibelle,
                r != null ? r.getId() : null, r != null ? r.getCode() : null,
                r != null && r.isAccesGlobal(),
                r != null ? r.codesPermissions() : Set.of(),
                h.getNoeudId(), h.getDocumentId(), h.isRuptureHeritage());
    }
}
