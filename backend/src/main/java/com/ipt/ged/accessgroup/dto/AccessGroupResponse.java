package com.ipt.ged.accessgroup.dto;

import com.ipt.ged.accessgroup.AccessGroup;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Données renvoyées au frontend pour un groupe d'accès (liste + détail).
 *
 * <p>Contrat inchangé par T-025 (écart 2) : un membre est désigné par sa fiche
 * employé ({@code users[].id}), que l'écran renvoie tel quel dans
 * {@code userIds}. En base, le membre est l'identité GED de cette fiche ; une
 * personne qui ne s'est encore jamais connectée figure aussi dans
 * {@code users}, et son identifiant d'employé dans {@code pendingUserIds}
 * (appartenance en attente de la première connexion, sans effet sur les droits
 * jusque-là).
 */
public record AccessGroupResponse(
        UUID id,
        String code,
        String name,
        List<Ref> workspaces,
        List<Ref> users,
        int workspacesCount,
        int usersCount,
        List<UUID> pendingUserIds
) {
    /** Référence légère (id + libellé) vers une entité liée. */
    public record Ref(UUID id, String label) {}

    /**
     * @param ws espaces couverts : nœuds où le groupe porte une habilitation
     *           (calculés par le service, le groupe n'en porte plus la liste)
     */
    public static AccessGroupResponse from(AccessGroup g, List<Ref> ws) {
        List<Ref> attente = g.getMembresEnAttente().stream()
                .map(e -> new Ref(e.getId(), e.getFullName())).toList();
        List<Ref> us = Stream.concat(g.getMembres().stream()
                        .map(u -> new Ref(u.getEmploye().getId(), u.getEmploye().getFullName())),
                attente.stream()).toList();
        return new AccessGroupResponse(
                g.getId(), g.getCode(), g.getName(),
                ws, us, ws.size(), us.size(), attente.stream().map(Ref::id).toList());
    }
}
