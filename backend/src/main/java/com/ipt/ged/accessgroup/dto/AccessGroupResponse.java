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

    public static AccessGroupResponse from(AccessGroup g) {
        List<Ref> ws = g.getWorkspaces().stream()
                .map(w -> new Ref(w.getId(), w.getName())).toList();
        List<Ref> us = g.getUsers().stream()
                .map(e -> new Ref(e.getId(), e.getFullName())).toList();
        return new AccessGroupResponse(
                g.getId(), g.getCode(), g.getName(),
                ws, us, ws.size(), us.size());
    }
}
