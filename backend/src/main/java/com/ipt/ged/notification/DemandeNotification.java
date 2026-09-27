package com.ipt.ged.notification;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Demande de notification adressée à {@link Notifications#envoyer}.
 *
 * <p>Les variables alimentent le modèle français du type
 * ({@code notification/modeles.properties}) : {@code document}, {@code espace},
 * {@code role}, {@code decision}, {@code motif}, {@code auteur},
 * {@code echeance}… Une variable absente est rendue par un tiret.
 *
 * @param type           cas de notification (trois familles seulement)
 * @param destinataires  identités GED des destinataires (doublons ignorés)
 * @param roleDestinataire code d'un rôle dont tous les porteurs sont destinataires
 *                       (ex. Agents d'archive pour l'échéance), résolu par
 *                       {@link AnnuaireDestinataires#porteursDuRole} ; peut être nul
 * @param objetType      nature de l'objet (DOCUMENT, ESPACE, CIRCUIT…)
 * @param objetId        identifiant de l'objet
 * @param variables      valeurs du modèle
 * @param lien           chemin relatif dans l'application (ex. {@code documents/<id>}), ou nul
 */
public record DemandeNotification(TypeNotification type, Collection<UUID> destinataires, String roleDestinataire,
                                  String objetType, UUID objetId, Map<String, ?> variables, String lien) {

    public DemandeNotification {
        Objects.requireNonNull(type, "type");
        destinataires = destinataires == null ? List.of() : List.copyOf(new LinkedHashSet<>(
                destinataires.stream().filter(Objects::nonNull).toList()));
        variables = variables == null ? Map.of() : variables;
    }

    /** Demande à des destinataires nommés. */
    public static DemandeNotification a(TypeNotification type, Collection<UUID> destinataires, String objetType,
                                        UUID objetId, Map<String, ?> variables, String lien) {
        return new DemandeNotification(type, destinataires, null, objetType, objetId, variables, lien);
    }

    /** Demande aux porteurs d'un rôle (ex. Agents d'archive). */
    public static DemandeNotification auRole(TypeNotification type, String role, String objetType, UUID objetId,
                                             Map<String, ?> variables, String lien) {
        return new DemandeNotification(type, List.of(), role, objetType, objetId, variables, lien);
    }

    Set<UUID> destinatairesNommes() {
        return new LinkedHashSet<>(destinataires);
    }
}
