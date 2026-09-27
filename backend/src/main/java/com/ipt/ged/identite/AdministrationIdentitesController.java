package com.ipt.ged.identite;

import com.ipt.ged.identite.session.ServiceSessions;
import com.ipt.ged.identite.session.SessionUtilisateur;
import com.ipt.ged.security.UtilisateurConnecte;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Administration des identités et des sessions (risque R26, décision D1).
 * Base : {@code /api/v1/admin/utilisateurs}, réservée au rôle Administrateur
 * (contrôlé dans la chaîne de sécurité).
 *
 * <p>Sans relecture de l'état du compte dans l'annuaire, une personne désactivée
 * garde sa session jusqu'à la borne absolue : l'Administrateur doit pouvoir la
 * couper immédiatement. C'est l'objet de {@code DELETE .../sessions}.
 */
@RestController
@RequestMapping("/api/v1/admin/utilisateurs")
public class AdministrationIdentitesController {

    /** Ligne de la liste des identités. */
    public record IdentiteAdmin(UUID id, String identifiant, String fullName, String email, String direction,
                                List<String> roles, Instant derniereConnexion, int sessionsOuvertes) {}

    /** Session ouverte (jeton courant d'une famille). */
    public record SessionAdmin(UUID sessionId, Instant derniereActivite, Instant expireLe,
                               String adresseIp, String agentUtilisateur) {}

    public record Revocation(int sessionsFermees) {}

    private final ServiceIdentites identites;
    private final ServiceSessions sessions;
    private final CacheAnnuaireRepository cache;
    private final UtilisateurRepository utilisateurs;

    public AdministrationIdentitesController(ServiceIdentites identites, ServiceSessions sessions,
                                             CacheAnnuaireRepository cache, UtilisateurRepository utilisateurs) {
        this.identites = identites;
        this.sessions = sessions;
        this.cache = cache;
        this.utilisateurs = utilisateurs;
    }

    @GetMapping
    public List<IdentiteAdmin> liste() {
        List<Utilisateur> toutes = identites.toutes();
        Map<UUID, EntreeCacheAnnuaire> fiches = cache.findByUtilisateurIdIn(
                        toutes.stream().map(Utilisateur::getId).toList()).stream()
                .collect(Collectors.toMap(EntreeCacheAnnuaire::getUtilisateurId, Function.identity()));
        return toutes.stream()
                .sorted(Comparator.comparing(u -> u.getIdentifiant().toLowerCase()))
                .map(u -> {
                    EntreeCacheAnnuaire f = fiches.get(u.getId());
                    return new IdentiteAdmin(u.getId(), u.getIdentifiant(), u.getEmploye().getFullName(),
                            f != null ? f.getCourriel() : null, f != null ? f.getDirection() : null,
                            List.copyOf(identites.roles(u).get(1)),
                            u.getDerniereConnexionLe(), sessions.ouvertes(u.getId()).size());
                })
                .toList();
    }

    @GetMapping("/{id}/sessions")
    public List<SessionAdmin> sessionsOuvertes(@PathVariable UUID id) {
        existe(id);
        return sessions.ouvertes(id).stream()
                .map(AdministrationIdentitesController::versAdmin)
                .toList();
    }

    /** Révoque immédiatement toutes les sessions de l'identité. */
    @DeleteMapping("/{id}/sessions")
    public Revocation revoquer(@PathVariable UUID id, @AuthenticationPrincipal UtilisateurConnecte admin) {
        existe(id);
        return new Revocation(sessions.revoquerToutes(id, admin.getUtilisateurId()));
    }

    private void existe(UUID id) {
        if (!utilisateurs.existsById(id)) {
            throw new EntityNotFoundException("Identité introuvable : " + id);
        }
    }

    private static SessionAdmin versAdmin(SessionUtilisateur s) {
        return new SessionAdmin(s.getFamilleId(), s.getDerniereActiviteLe(), s.getExpireLe(),
                s.getAdresseIp(), s.getAgentUtilisateur());
    }
}
