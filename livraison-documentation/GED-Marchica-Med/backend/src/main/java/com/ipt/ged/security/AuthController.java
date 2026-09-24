package com.ipt.ged.security;

import com.ipt.ged.security.dto.DemandeConnexion;
import com.ipt.ged.security.dto.ReponseConnexion;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

/**
 * Connexion et identité de l'appelant.
 *
 * <p>{@code /login} est la seule route ouverte de l'API. {@code /me} exige un
 * jeton valide : c'est elle que le frontend interroge au démarrage pour savoir
 * si la session mémorisée dans le navigateur vaut encore quelque chose.
 *
 * <p>Aucune réponse ne distingue « e-mail inconnu » de « mot de passe faux » :
 * la différence permettrait d'énumérer les comptes existants.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String MESSAGE_REFUS = "E-mail ou mot de passe incorrect.";

    private final AuthenticationManager authentification;
    private final ServiceJeton jetons;
    private final CompteUtilisateurRepository comptes;
    private final LimiteurTentatives limiteur;

    public AuthController(AuthenticationManager authentification,
                          ServiceJeton jetons,
                          CompteUtilisateurRepository comptes,
                          LimiteurTentatives limiteur) {
        this.authentification = authentification;
        this.jetons = jetons;
        this.comptes = comptes;
        this.limiteur = limiteur;
    }

    @PostMapping("/login")
    public ResponseEntity<ReponseConnexion> connexion(@Valid @RequestBody DemandeConnexion demande) {
        String email = demande.email().trim().toLowerCase();

        if (limiteur.estBloque(email)) {
            long minutes = limiteur.minutesRestantes(email);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Trop de tentatives. Réessayez dans " + minutes + " minute(s).");
        }

        try {
            var authentifie = authentification.authenticate(
                    new UsernamePasswordAuthenticationToken(email, demande.motDePasse()));

            UtilisateurConnecte utilisateur = (UtilisateurConnecte) authentifie.getPrincipal();
            limiteur.succes(email);
            tracerConnexion(utilisateur);

            return ResponseEntity.ok(construire(utilisateur));

        } catch (DisabledException e) {
            // Compte volontairement désactivé : le dire est utile et ne révèle
            // rien qu'un administrateur n'ait décidé.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Ce compte est désactivé. Contactez un administrateur.");
        } catch (AuthenticationException e) {
            // Couvre BadCredentialsException, UsernameNotFoundException masquée
            // et les autres refus : tous doivent produire la MÊME réponse.
            limiteur.echec(email);
            log.info("Connexion refusée pour {}", email);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, MESSAGE_REFUS);
        }
    }

    /** Identité de l'appelant, telle que le jeton la désigne. */
    @GetMapping("/me")
    public ReponseConnexion moi(@AuthenticationPrincipal UtilisateurConnecte utilisateur) {
        if (utilisateur == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        // Pas de nouveau jeton ici : celui que le client détient reste le sien.
        return construire(utilisateur, null);
    }

    /**
     * Déconnexion. Sans état côté serveur, il n'y a rien à invalider : le
     * client jette son jeton. La route existe pour que le frontend ait un point
     * d'appel explicite, et pour laisser la place à une liste de révocation si
     * le besoin apparaît.
     */
    @PostMapping("/logout")
    public Map<String, String> deconnexion() {
        return Map.of("statut", "deconnecte");
    }

    private void tracerConnexion(UtilisateurConnecte utilisateur) {
        comptes.findById(utilisateur.getCompte().getId()).ifPresent(c -> {
            c.setDerniereConnexion(Instant.now());
            comptes.save(c);
        });
    }

    private ReponseConnexion construire(UtilisateurConnecte utilisateur) {
        return construire(utilisateur, jetons.emettre(utilisateur));
    }

    private ReponseConnexion construire(UtilisateurConnecte utilisateur, String jeton) {
        var employe = utilisateur.getCompte().getEmploye();
        return new ReponseConnexion(
                jeton,
                jeton == null ? null : "Bearer",
                jetons.validiteSecondes(),
                employe.getId(),
                utilisateur.getUsername(),
                employe.getFullName());
    }
}
