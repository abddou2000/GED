package com.ipt.ged.identite.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Ce que le formulaire de connexion envoie.
 *
 * <p>L'identifiant est le {@code sAMAccountName} de l'annuaire, et lui seul
 * (décision client D2) : une adresse e-mail ou un {@code userPrincipalName}
 * (contenant « @ ») est refusé avant tout appel à l'annuaire. Les caractères
 * interdits dans un {@code sAMAccountName} le sont aussi, comme les caractères
 * de contrôle — l'identifiant finit dans les journaux.
 *
 * <p>La borne haute du mot de passe empêche qu'un corps de plusieurs mégaoctets
 * parte vers l'annuaire.
 */
public record DemandeConnexion(
        @NotBlank(message = "L'identifiant est obligatoire")
        @Size(max = 64, message = "Identifiant trop long")
        @Pattern(regexp = "^[^@\"/\\\\\\[\\]:;|=,+*?<>\\p{Cntrl}]+$",
                message = "Connectez-vous avec votre identifiant Windows, pas avec votre adresse e-mail.")
        String identifiant,

        @NotBlank(message = "Le mot de passe est obligatoire")
        @Size(max = 256)
        String motDePasse) {

    /** Jamais de mot de passe dans une trace, même par mégarde. */
    @Override
    public String toString() {
        return "DemandeConnexion[identifiant=" + identifiant + ", motDePasse=***]";
    }
}
