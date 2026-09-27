package com.ipt.ged.cleapi;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Réglages des clés d'API (DAT §5.4), sous {@code ged.api.cles}.
 *
 * @param environnement préfixe des clés émises ({@code dev}, {@code uat}, {@code prod})
 * @param validite      durée de validité d'une clé (12 mois par défaut)
 * @param chevauchement durée pendant laquelle l'ancienne clé reste valide après régénération
 * @param alerteExpiration délai avant expiration à partir duquel la clé est signalée
 * @param quotaMinute   quota par défaut d'une nouvelle application, par minute et par clé
 * @param quotaJour     quota par défaut d'une nouvelle application, par jour et par clé
 * @param persistanceCompteurs fréquence de persistance des compteurs de quotas
 */
@ConfigurationProperties("ged.api.cles")
public record ProprietesCleApi(String environnement, Duration validite, Duration chevauchement,
                               Duration alerteExpiration, Integer quotaMinute, Integer quotaJour,
                               Duration persistanceCompteurs) {

    public ProprietesCleApi {
        environnement = environnement == null || environnement.isBlank() ? "dev" : environnement.trim().toLowerCase();
        validite = validite == null ? Duration.ofDays(365) : validite;
        chevauchement = chevauchement == null ? Duration.ofDays(7) : chevauchement;
        alerteExpiration = alerteExpiration == null ? Duration.ofDays(30) : alerteExpiration;
        quotaMinute = quotaMinute == null ? 600 : quotaMinute;
        quotaJour = quotaJour == null ? 100_000 : quotaJour;
        persistanceCompteurs = persistanceCompteurs == null ? Duration.ofMinutes(1) : persistanceCompteurs;
    }
}
