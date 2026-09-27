package com.ipt.ged.depot.source;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Origine du dépôt en cours : celle du point d'extension {@link SourceDepot},
 * le bureau d'ordre étant reconnu à son code d'application
 * ({@code ged.depot.applications-bureau-ordre}) — il reste une application
 * cliente parmi d'autres, sans traitement particulier (§5.2).
 */
@Component
public class ResolutionOrigineDepot {

    private final SourceDepot source;
    private final Set<String> bureauOrdre;

    public ResolutionOrigineDepot(SourceDepot source,
                                  @Value("${ged.depot.applications-bureau-ordre:}") List<String> bureauOrdre) {
        this.source = source;
        this.bureauOrdre = bureauOrdre.stream().map(String::trim).filter(s -> !s.isEmpty())
                .map(String::toUpperCase).collect(Collectors.toUnmodifiableSet());
    }

    public SourceDepot.Origine courante() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        SourceDepot.Origine o = source.origine(a);
        if (o.canal() == CanalDepot.API && o.applicationCode() != null
                && bureauOrdre.contains(sansPrefixe(o.applicationCode()).toUpperCase())) {
            return new SourceDepot.Origine(CanalDepot.BUREAU_ORDRE, o.applicationId(), o.applicationCode(),
                    o.deposantUtilisateurId(), o.delegue());
        }
        return o;
    }

    /** « application:BO » (nom d'authentification du lot clés d'API) → « BO ». */
    private static String sansPrefixe(String code) {
        return code.startsWith("application:") ? code.substring("application:".length()) : code;
    }
}
