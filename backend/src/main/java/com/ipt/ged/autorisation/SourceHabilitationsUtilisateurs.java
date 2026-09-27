package com.ipt.ged.autorisation;

import com.ipt.ged.accessgroup.AccessGroup;
import com.ipt.ged.accessgroup.AccessGroupRepository;
import com.ipt.ged.security.UtilisateurConnecte;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Source des identités GED : les habilitations de la personne et celles des
 * groupes GED dont elle est membre (§12.2.2, cumul en union). Aucune n'est
 * jamais déduite de l'annuaire (P2).
 *
 * <p>Les habilitations de sujet APPLICATION posées dans la même table sont
 * aussi servies ici, pour le jour où une autre source reconnaîtra l'application
 * appelante.
 */
@Component
@Order(0)
public class SourceHabilitationsUtilisateurs implements SourceHabilitations {

    private final HabilitationRepository habilitations;
    private final AccessGroupRepository groupes;

    public SourceHabilitationsUtilisateurs(HabilitationRepository habilitations, AccessGroupRepository groupes) {
        this.habilitations = habilitations;
        this.groupes = groupes;
    }

    @Override
    public Optional<Sujet> sujet(Authentication authentification) {
        if (authentification != null && authentification.getPrincipal() instanceof UtilisateurConnecte u) {
            return Optional.of(Sujet.utilisateur(u));
        }
        return Optional.empty();
    }

    @Override
    public List<Attribution> attributions(Sujet sujet) {
        if (sujet.type() == TypeSujet.APPLICATION) {
            return habilitations.findByApplicationId(sujet.id()).stream()
                    .map(h -> Attribution.depuis(h, sujet.libelle())).toList();
        }
        List<Habilitation> lignes = habilitations.applicablesA(sujet.id(), sujet.employeId());
        List<UUID> idsGroupes = lignes.stream().map(Habilitation::getGroupeGedId).filter(Objects::nonNull)
                .distinct().toList();
        Map<UUID, String> noms = idsGroupes.isEmpty() ? Map.of() : groupes.findAllById(idsGroupes).stream()
                .collect(Collectors.toMap(AccessGroup::getId, AccessGroup::getName, (a, b) -> a));
        return lignes.stream()
                .map(h -> Attribution.depuis(h, h.getSujetType() == TypeSujet.GROUPE
                        ? noms.getOrDefault(h.getGroupeGedId(), "Groupe") : sujet.libelle()))
                .toList();
    }
}
