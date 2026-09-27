package com.ipt.ged.audit;

import com.ipt.ged.common.erreur.AccesRefuseException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Câblage du journal d'audit. */
@Configuration
public class ConfigurationAudit {

    /**
     * Garde provisoire de la consultation : appelant authentifié. Remplacée par
     * celle du lot autorisation (permission {@code CONSULTER_AUDIT}) dès qu'un
     * bean {@link GardeConsultationAudit} est déclaré.
     */
    @Bean
    @ConditionalOnMissingBean(GardeConsultationAudit.class)
    public GardeConsultationAudit gardeConsultationAuthentifie() {
        return () -> {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            if (a == null || !a.isAuthenticated() || a instanceof AnonymousAuthenticationToken) {
                throw new AccesRefuseException("Consultation du journal d'audit réservée à l'Administrateur.");
            }
        };
    }
}
