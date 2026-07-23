package com.ipt.ged.accessgroup.dto;

import com.ipt.ged.accessgroup.AccessGroup;

import java.util.List;

/**
 * Données renvoyées au frontend pour un groupe d'accès (liste + détail).
 */
public record AccessGroupResponse(
        Long id,
        String code,
        String name,
        GedRightsDto rights,
        List<Ref> workspaces,
        List<Ref> users,
        int workspacesCount,
        int usersCount
) {
    /** Référence légère (id + libellé) vers une entité liée. */
    public record Ref(Long id, String label) {}

    public static AccessGroupResponse from(AccessGroup g) {
        List<Ref> ws = g.getWorkspaces().stream()
                .map(w -> new Ref(w.getId(), w.getName())).toList();
        List<Ref> us = g.getUsers().stream()
                .map(e -> new Ref(e.getId(), e.getFullName())).toList();
        return new AccessGroupResponse(
                g.getId(), g.getCode(), g.getName(),
                GedRightsDto.from(g.getRights()),
                ws, us, ws.size(), us.size());
    }
}
