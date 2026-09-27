package com.ipt.ged.cleapi.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Objets d'échange de l'administration des applications et des clés d'API. */
public final class DtoCleApi {

    private DtoCleApi() {}

    /** Création ou modification d'une application. */
    public record ApplicationRequest(
            @NotBlank(message = "Le code est obligatoire")
            @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,63}$",
                    message = "Code : minuscules, chiffres et tirets, 2 à 64 caractères")
            String code,
            @NotBlank(message = "Le nom est obligatoire") @Size(max = 255) String nom,
            @Size(max = 1000) String description,
            List<@NotBlank @Size(max = 49) String> adressesAutorisees,
            @Min(value = 1, message = "Quota par minute : au moins 1") @Max(100_000) Integer quotaMinute,
            @Min(value = 1, message = "Quota par jour : au moins 1") @Max(100_000_000) Integer quotaJour) {}

    public record ActivationRequest(boolean active) {}

    /** Génération d'une clé : délégation (§5.5) et validité facultative (jours). */
    public record GenerationRequest(boolean delegation, @Min(1) @Max(730) Integer validiteJours) {}

    public record RevocationRequest(@NotBlank(message = "Le motif est obligatoire") @Size(max = 500) String motif) {}

    /** Clé telle qu'on la présente : jamais le secret ni son empreinte. */
    public record CleResponse(UUID id, String identifiant, String environnement, boolean delegation,
                              Instant creeLe, Instant expireLe, Instant revoqueeLe, String motifRevocation,
                              UUID remplaceeParCleApiId, Instant derniereUtilisation, long appelsDuJour,
                              String etat, boolean expireBientot) {}

    public record ApplicationResponse(UUID id, String code, String nom, String description,
                                      List<String> adressesAutorisees, int quotaMinute, int quotaJour,
                                      boolean active, Instant creeLe, Instant modifieLe,
                                      List<CleResponse> cles, int clesExpirantBientot) {}

    /**
     * Clé générée : {@code cle} contient la valeur complète, affichée UNE SEULE FOIS
     * (DAT §5.4). Elle n'est ni stockée ni relisible ensuite.
     */
    public record CleGenereeResponse(String cle, CleResponse details) {}
}
