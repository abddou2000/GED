package com.ipt.ged.identite;

import com.ipt.ged.identite.dto.DemandeConnexion;
import com.ipt.ged.identite.dto.IdentiteResponse;
import com.ipt.ged.identite.dto.ReponseConnexion;
import com.ipt.ged.identite.erreur.EnteteCsrfManquantException;
import com.ipt.ged.identite.erreur.RenouvellementRefuseException;
import com.ipt.ged.identite.session.ServiceSessions;
import com.ipt.ged.security.UtilisateurConnecte;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Connexion, renouvellement, déconnexion et identité de l'appelant
 * (dossier technique §3.3, §3.4.1). Base : {@code /api/v1/auth}.
 *
 * <ul>
 *   <li>{@code POST /login} : identifiant d'annuaire + mot de passe → jeton
 *       d'accès dans le corps, jeton de renouvellement en cookie
 *       {@code HttpOnly; Secure; SameSite=Strict}, limité au chemin
 *       {@code /api/v1/auth}.</li>
 *   <li>{@code POST /refresh} : fondé sur le cookie, il exige EN PLUS l'en-tête
 *       personnalisé {@code X-GED-Renouvellement} (protection CSRF, P-03).</li>
 *   <li>{@code POST /logout} : révoque la session (cookie ou jeton d'accès).</li>
 *   <li>{@code GET /me} : identité et rôles de l'appelant.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final ServiceConnexion connexion;
    private final ServiceSessions sessions;
    private final ServiceCacheAnnuaire cache;
    private final ProprietesIdentite proprietes;

    public AuthController(ServiceConnexion connexion, ServiceSessions sessions, ServiceCacheAnnuaire cache,
                          ProprietesIdentite proprietes) {
        this.connexion = connexion;
        this.sessions = sessions;
        this.cache = cache;
        this.proprietes = proprietes;
    }

    @PostMapping("/login")
    public ResponseEntity<ReponseConnexion> connexion(@Valid @RequestBody DemandeConnexion demande,
                                                      HttpServletRequest requete) {
        ServiceConnexion.Ouverture o = connexion.connecter(demande.identifiant(), demande.motDePasse(),
                requete.getRemoteAddr(), requete.getHeader(HttpHeaders.USER_AGENT));
        return reponse(o);
    }

    @PostMapping("/refresh")
    public ResponseEntity<ReponseConnexion> renouvellement(HttpServletRequest requete) {
        exigerEnteteCsrf(requete);
        String valeur = cookie(requete);
        if (valeur == null) {
            throw new RenouvellementRefuseException();
        }
        return reponse(connexion.renouveler(valeur, requete.getRemoteAddr(),
                requete.getHeader(HttpHeaders.USER_AGENT)));
    }

    /**
     * Déconnexion : la session est révoquée côté serveur. Le jeton d'accès en
     * cours cesse aussitôt d'être accepté (le filtre vérifie la session à chaque
     * requête), et le cookie est effacé.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> deconnexion(HttpServletRequest requete,
                                            @AuthenticationPrincipal UtilisateurConnecte principal) {
        String valeur = cookie(requete);
        if (valeur != null) {
            exigerEnteteCsrf(requete);
            sessions.fermerParJeton(valeur);
        }
        if (principal != null && principal.getSessionId() != null) {
            sessions.fermerFamille(principal.getUtilisateurId(), principal.getSessionId());
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookieEfface().toString())
                .build();
    }

    @GetMapping("/me")
    public IdentiteResponse moi(@AuthenticationPrincipal UtilisateurConnecte principal) {
        return identite(principal);
    }

    private ResponseEntity<ReponseConnexion> reponse(ServiceConnexion.Ouverture o) {
        ReponseConnexion corps = new ReponseConnexion(o.jetonAcces(), "Bearer", o.expireDansSecondes(),
                identite(o.utilisateur()));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookieRenouvellement(o.renouvellement()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(corps);
    }

    private IdentiteResponse identite(UtilisateurConnecte u) {
        var entree = cache.lire(u.getUtilisateurId());
        return new IdentiteResponse(u.getUtilisateurId(), u.getUsername(), u.getEmployeId(), u.getNomComplet(),
                entree.map(EntreeCacheAnnuaire::getCourriel).orElse(null),
                entree.map(EntreeCacheAnnuaire::getDirection).orElse(null),
                List.copyOf(new java.util.TreeSet<>(u.getRoles())));
    }

    private ResponseCookie cookieRenouvellement(ServiceSessions.JetonRenouvellement r) {
        ProprietesIdentite.Session s = proprietes.getSession();
        Duration restant = Duration.between(Instant.now(), r.expireLe());
        return ResponseCookie.from(s.getNomCookie(), r.valeur())
                .httpOnly(true)
                .secure(s.isCookieSecurise())
                .sameSite("Strict")
                .path(s.getCheminCookie())
                .maxAge(restant.isNegative() ? Duration.ZERO : restant)
                .build();
    }

    private ResponseCookie cookieEfface() {
        ProprietesIdentite.Session s = proprietes.getSession();
        return ResponseCookie.from(s.getNomCookie(), "")
                .httpOnly(true)
                .secure(s.isCookieSecurise())
                .sameSite("Strict")
                .path(s.getCheminCookie())
                .maxAge(Duration.ZERO)
                .build();
    }

    private String cookie(HttpServletRequest requete) {
        if (requete.getCookies() == null) return null;
        for (Cookie c : requete.getCookies()) {
            if (proprietes.getSession().getNomCookie().equals(c.getName()) && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }
        return null;
    }

    private void exigerEnteteCsrf(HttpServletRequest requete) {
        String entete = proprietes.getSession().getEnteteCsrf();
        String valeur = requete.getHeader(entete);
        if (valeur == null || valeur.isBlank()) {
            throw new EnteteCsrfManquantException(entete);
        }
    }
}
