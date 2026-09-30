package com.ipt.ged.cleapi;

import com.ipt.ged.audit.ActionAudit;
import com.ipt.ged.audit.AuditService;
import com.ipt.ged.audit.EntreeAudit;
import com.ipt.ged.common.erreur.ExceptionMetier;
import com.ipt.ged.common.erreur.ReponsesSecuriteProblem;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authentifie une application par sa clé d'API ({@code X-API-Key}, DAT §5.4)
 * dans la chaîne de sécurité dédiée aux applications
 * ({@link ConfigurationSecuriteApplications}).
 *
 * <p>Sur succès : l'application devient le sujet de la requête (contexte de
 * sécurité), son identifiant est attaché à la requête pour le journal d'audit
 * ({@code acteur_application_id}), et l'appel est lui-même tracé
 * ({@code APPEL_API}, DAT §7.4.1 : « chaque appel API doit être attribuable à
 * l'application appelante »).
 *
 * <p>Sur refus : réponse problem+json (401, 403 ou 429 avec {@code Retry-After})
 * et trace {@code CLE_API_REFUSEE} ou {@code QUOTA_DEPASSE}. Ni le secret ni
 * son empreinte n'apparaissent jamais dans une trace : seulement l'identifiant
 * public de la clé.
 */
public class FiltreCleApi extends OncePerRequestFilter {

    public static final String ENTETE_CLE = "X-API-Key";
    public static final String ENTETE_DELEGATION = "X-On-Behalf-Of";
    /** Attribut de requête : identité déléguée résolue (vague 4). */
    public static final String ATTRIBUT_DELEGATION = FiltreCleApi.class.getName() + ".delegation";

    private static final Logger journal = LoggerFactory.getLogger(FiltreCleApi.class);

    private final AuthentificationCleApi authentification;
    private final ResolveurIdentiteDeleguee delegation;
    private final ReponsesSecuriteProblem reponses;
    private final AuditService audit;

    public FiltreCleApi(AuthentificationCleApi authentification, ResolveurIdentiteDeleguee delegation,
                        ReponsesSecuriteProblem reponses, AuditService audit) {
        this.authentification = authentification;
        this.delegation = delegation;
        this.reponses = reponses;
        this.audit = audit;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest requete, @NonNull HttpServletResponse reponse,
                                    @NonNull FilterChain suite) throws ServletException, IOException {
        AuthentificationCleApi.Resultat r = authentification.verifier(requete.getHeader(ENTETE_CLE),
                requete.getRemoteAddr());
        if (r.refus() != null) {
            refuser(requete, reponse, r.refus());
            return;
        }
        ApplicationAuthentifiee application = r.application();
        application.setDetails(requete.getRemoteAddr());
        SecurityContext contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(application);
        SecurityContextHolder.setContext(contexte);
        requete.setAttribute(AuditService.ATTRIBUT_APPLICATION, application.applicationId());

        String deleguee = requete.getHeader(ENTETE_DELEGATION);
        if (deleguee != null) {
            if (!application.delegation()) {
                tracerRefus(requete, CodesErreurCleApi.DELEGATION_NON_AUTORISEE, application, null);
                reponses.ecrire(requete, reponse, HttpStatus.FORBIDDEN, CodesErreurCleApi.DELEGATION_NON_AUTORISEE,
                        "Cette clé n'est pas habilitée à agir pour le compte d'un utilisateur.");
                return;
            }
            try {
                ResolveurIdentiteDeleguee.IdentiteDeleguee identite = delegation.resoudre(application, deleguee);
                requete.setAttribute(ATTRIBUT_DELEGATION, identite);
                // Double identité (§5.5) : l'utilisateur devient le principal (auteur,
                // acteur_utilisateur_id), l'application reste le sujet des droits et
                // acteur_application_id. Lecture = intersection des droits.
                boolean lecture = "GET".equals(requete.getMethod()) || "HEAD".equals(requete.getMethod());
                application = application.avecDelegation(identite.principal(), lecture);
                SecurityContext delegue = SecurityContextHolder.createEmptyContext();
                delegue.setAuthentication(application);
                SecurityContextHolder.setContext(delegue);
            } catch (ExceptionMetier e) {
                // Cause précise (compte désactivé, D15…) au journal seulement : le client
                // reçoit le même libellé quelle que soit la cause.
                Object motif = e.proprietes().get(ResolveurDelegationAnnuaire.MOTIF_AUDIT);
                tracerRefus(requete, e.code(), application, motif == null ? null : motif.toString());
                reponses.ecrire(requete, reponse, e.statut(), e.code(), e.getMessage());
                return;
            }
        }

        try {
            suite.doFilter(requete, reponse);
        } finally {
            tracerAppel(requete, reponse, application);
        }
    }

    private void refuser(HttpServletRequest requete, HttpServletResponse reponse,
                         AuthentificationCleApi.Refus refus) throws IOException {
        boolean quota = refus.statut() == HttpStatus.TOO_MANY_REQUESTS;
        if (quota) {
            long secondes = Math.max(1, refus.reessayerApres().toSeconds()
                    + (refus.reessayerApres().toNanosPart() > 0 ? 1 : 0));
            reponse.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(secondes));
        }
        try {
            audit.enregistrerHorsTransaction(EntreeAudit.de(quota ? ActionAudit.QUOTA_DEPASSE : ActionAudit.CLE_API_REFUSEE,
                            refus.applicationId() != null ? "APPLICATION" : null, refus.applicationId())
                    .parActeur(null, refus.applicationId(), refus.identifiant() == null ? null : "cle:" + refus.identifiant(), null)
                    .refus(refus.code() + " " + requete.getMethod() + " " + requete.getRequestURI()));
        } catch (RuntimeException e) {
            journal.error("Refus de clé d'API non tracé au journal d'audit", e);
        }
        reponses.ecrire(requete, reponse, refus.statut(), refus.code(), refus.detail());
    }

    private void tracerRefus(HttpServletRequest requete, String code, ApplicationAuthentifiee app, String motif) {
        try {
            audit.enregistrerHorsTransaction(EntreeAudit.de(ActionAudit.CLE_API_REFUSEE, "APPLICATION", app.applicationId())
                    .refus(code + " " + requete.getMethod() + " " + requete.getRequestURI()
                            + (motif != null ? " : " + motif : "")));
        } catch (RuntimeException e) {
            journal.error("Refus de délégation non tracé au journal d'audit", e);
        }
    }

    private void tracerAppel(HttpServletRequest requete, HttpServletResponse reponse, ApplicationAuthentifiee app) {
        try {
            Map<String, Object> appel = new LinkedHashMap<>();
            appel.put("methode", requete.getMethod());
            appel.put("chemin", requete.getRequestURI());
            appel.put("statut", reponse.getStatus());
            appel.put("cle", app.cleId().toString());
            audit.enregistrerHorsTransaction(EntreeAudit.de(ActionAudit.APPEL_API, "APPLICATION", app.applicationId())
                    .avecApres(appel));
        } catch (RuntimeException e) {
            journal.error("Appel d'API non tracé au journal d'audit", e);
        }
    }

    /** Ne traite que les requêtes qui présentent une clé ; les autres ne passent pas par cette chaîne. */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest requete) {
        return requete.getHeader(ENTETE_CLE) == null;
    }
}
