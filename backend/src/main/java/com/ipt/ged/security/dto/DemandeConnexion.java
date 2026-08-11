package com.ipt.ged.security.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Ce que le formulaire de connexion envoie.
 *
 * <p>La borne haute sur le mot de passe n'est pas une contrainte de politique :
 * elle empêche qu'un envoi de plusieurs mégaoctets fasse travailler BCrypt pour
 * rien — un déni de service à coût nul pour l'attaquant.
 */
public record DemandeConnexion(
        @NotBlank(message = "L'e-mail est obligatoire")
        @Email(message = "Format d'e-mail invalide")
        @Size(max = 190)
        String email,

        @NotBlank(message = "Le mot de passe est obligatoire")
        @Size(max = 200)
        String motDePasse) {
}
