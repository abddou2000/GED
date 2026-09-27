package com.ipt.ged.accessgroup.dto;

import com.ipt.ged.accessgroup.AccessGroup;

import java.util.List;
import java.util.UUID;

/**
 * Données renvoyées au frontend pour un groupe d'accès (liste + détail).
 */
public record AccessGroupResponse(
        UUID id,
        String code,
        String name,
        List<Ref> workspaces,
        List<Ref> users,
        int workspacesCount,
        int usersCount
) {
    /** Référence légère (id + libellé) vers une entité liée. */
    public record Ref(UUID id, String label) {}

    /**
     * @param ws espaces couverts : nœuds où le groupe porte une habilitation
     *           (calculés par le service, le groupe n'en porte plus la liste)
     */
    public static AccessGroupResponse from(AccessGroup g, List<Ref> ws) {
        List<Ref> us = g.getUsers().stream()
                .map(e -> new Ref(e.getId(), e.getFullName())).toList();
        return new AccessGroupResponse(
                g.getId(), g.getCode(), g.getName(),
                ws, us, ws.size(), us.size());
    }
}
