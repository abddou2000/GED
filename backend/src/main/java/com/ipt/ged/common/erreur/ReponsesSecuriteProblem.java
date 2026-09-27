package com.ipt.ged.common.erreur;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.audit.ActionAudit;
import com.ipt.ged.audit.AuditService;
import com.ipt.ged.audit.EntreeAudit;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Réponses 401 et 403 de la chaîne Spring Security, au même format
 * {@code application/problem+json} que les erreurs des contrôleurs.
 *
 * <p>Ces refus sont émis par les filtres, avant tout contrôleur : le
 * gestionnaire MVC ne les voit jamais. À brancher dans la configuration de
 * sécurité (propriétaire : lot identité) :
 * <pre>{@code
 * .exceptionHandling(e -> e
 *         .authenticationEntryPoint(reponsesSecurite)
 *         .accessDeniedHandler(reponsesSecurite))
 * }</pre>
 *
 * <p>Le {@code detail} ne dit jamais <i>pourquoi</i> l'authentification a
 * échoué (jeton expiré, signature invalide, compte inconnu) : la distinction
 * aiderait un attaquant. Elle figure au journal technique, avec le traceId.
 */
@Component
public class ReponsesSecuriteProblem implements AuthenticationEntryPoint, AccessDeniedHandler {

    static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private final ObjectMapper json;
    private final ObjectProvider<AuditService> audit;

    @Autowired
    public ReponsesSecuriteProblem(ObjectMapper json, ObjectProvider<AuditService> audit) {
        this.audit = audit;
        // Le mixin met à plat les propriétés d'extension (code, traceId) comme
        // le fait Spring MVC ; ajouté ici pour ne pas dépendre de la façon dont
        // l'ObjectMapper reçu a été construit.
        this.json = json.copy().addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);
    }

    /** Sans journal d'audit (tests unitaires). */
    public ReponsesSecuriteProblem(ObjectMapper json) {
        this.json = json.copy().addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);
        this.audit = null;
    }

    @Override
    public void commence(HttpServletRequest requete, HttpServletResponse reponse,
                         AuthenticationException exception) throws IOException {
        ecrire(requete, reponse, HttpStatus.UNAUTHORIZED, CodesErreur.NON_AUTHENTIFIE,
                "Authentification requise : jeton absent, invalide ou expiré.");
    }

    @Override
    public void handle(HttpServletRequest requete, HttpServletResponse reponse,
                       AccessDeniedException exception) throws IOException {
        // Refus de droits tracé (DAT §7.4.1), comme ceux des contrôleurs.
        AuditService journalAudit = audit != null ? audit.getIfAvailable() : null;
        if (journalAudit != null) {
            try {
                journalAudit.enregistrer(EntreeAudit.de(ActionAudit.ACCES_REFUSE)
                        .refus(CodesErreur.ACCES_REFUSE + " " + requete.getMethod() + " " + requete.getRequestURI()));
            } catch (RuntimeException e) {
                LoggerFactory.getLogger(ReponsesSecuriteProblem.class).error("Refus de droits non tracé", e);
            }
        }
        ecrire(requete, reponse, HttpStatus.FORBIDDEN, CodesErreur.ACCES_REFUSE, "Accès refusé.");
    }

    /**
     * Écrit une réponse problem+json depuis un filtre, hors de Spring MVC (refus
     * d'une clé d'API, quota…). Sans effet si la réponse est déjà engagée.
     */
    public void ecrire(HttpServletRequest requete, HttpServletResponse reponse, HttpStatus statut,
                       String code, String detail) throws IOException {
        if (reponse.isCommitted()) return;
        ProblemDetail probleme = Problemes.creer(statut, code, detail, requete);
        reponse.setStatus(statut.value());
        reponse.setContentType(PROBLEM_JSON.toString());
        reponse.setCharacterEncoding("UTF-8");
        json.writeValue(reponse.getOutputStream(), probleme);
    }
}
