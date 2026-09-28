package com.ipt.ged.securite;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Chiffrement des liaisons (DAT §6.2.1, T-065) vérifié au démarrage, en échec
 * fermé, dans les profils d'exploitation ({@code prod}, {@code uat}) :
 *
 * <ul>
 *   <li><b>PostgreSQL</b> : {@code sslmode=verify-full} (chiffrement ET
 *       vérification du nom et du certificat du serveur) et autorité
 *       ({@code sslrootcert}) présente et lisible ;</li>
 *   <li><b>annuaire</b> : LDAPS exigé ({@code ged.identite.annuaire.exiger-ldaps})
 *       et aucune URL {@code ldap://} ;</li>
 *   <li><b>relais SMTP</b> : STARTTLS exigé.</li>
 * </ul>
 * Les valeurs par défaut des profils sont déjà sûres ; ce contrôle empêche
 * qu'une variable d'environnement ({@code DB_SSLMODE=disable},
 * {@code GED_SMTP_STARTTLS=false}…) les affaiblisse en silence : l'application
 * refuse alors de démarrer et dit pourquoi. Aucun effet en dev et en test.
 */
@Component
public class ControleTransportsChiffres implements InitializingBean {

    private static final List<String> PROFILS_EXPLOITATION = List.of("prod", "uat");

    private final Environment env;

    public ControleTransportsChiffres(Environment env) {
        this.env = env;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> ecarts = ecarts();
        if (!ecarts.isEmpty()) {
            throw new IllegalStateException("Liaisons non chiffrées refusées en exploitation (DAT §6.2.1) : "
                    + String.join(" ; ", ecarts));
        }
    }

    /** Écarts à la politique de chiffrement ; vide hors profils d'exploitation. */
    List<String> ecarts() {
        boolean exploitation = Arrays.stream(env.getActiveProfiles()).anyMatch(PROFILS_EXPLOITATION::contains);
        if (!exploitation) return List.of();
        List<String> ecarts = new ArrayList<>();

        String sslmode = env.getProperty("ged.base.sslmode", "");
        if (!"verify-full".equalsIgnoreCase(sslmode.trim())) {
            ecarts.add("PostgreSQL : sslmode=" + sslmode + " (verify-full exigé, DB_SSLMODE)");
        }
        String url = env.getProperty("spring.datasource.url", "");
        if (!url.toLowerCase(Locale.ROOT).contains("sslmode=verify-full")) {
            ecarts.add("PostgreSQL : l'URL JDBC ne porte pas sslmode=verify-full");
        }
        String autorite = env.getProperty("spring.datasource.hikari.data-source-properties.sslrootcert", "");
        if (autorite.isBlank() || !Files.isReadable(Path.of(autorite))) {
            ecarts.add("PostgreSQL : autorité de certification illisible (" + autorite + ", DB_SSLROOTCERT)");
        }

        if (!env.getProperty("ged.identite.annuaire.exiger-ldaps", Boolean.class, true)) {
            ecarts.add("annuaire : LDAPS non exigé (ged.identite.annuaire.exiger-ldaps=false)");
        }
        String urlsAnnuaire = env.getProperty("ged.identite.annuaire.urls", "");
        for (String u : urlsAnnuaire.split(",")) {
            if (!u.isBlank() && !u.trim().toLowerCase(Locale.ROOT).startsWith("ldaps://")) {
                ecarts.add("annuaire : URL non chiffrée " + u.trim() + " (GED_LDAP_URLS)");
            }
        }

        if (!env.getProperty("spring.mail.properties.mail.smtp.starttls.required", Boolean.class, false)) {
            ecarts.add("relais SMTP : STARTTLS non exigé (GED_SMTP_STARTTLS)");
        }
        return ecarts;
    }
}
