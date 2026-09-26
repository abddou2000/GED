package com.ipt.ged.employe.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Fiche de profil d'un utilisateur : identité et activité réelle dans la GED.
 *
 * <p>Tout ce qui figure ici est calculé à partir des données existantes —
 * dossiers dont il est propriétaire, groupes qui l'incluent, documents qu'il a
 * déposés, signatures qui l'attendent. Rien n'est estimé ni complété : un profil
 * qui afficherait des chiffres décoratifs induirait en erreur sur les
 * responsabilités réelles d'une personne.
 */
public record ProfilResponse(
        UUID id,
        String fullName,
        String firstName,
        String lastName,
        /** Possède un compte de connexion — donc peut approuver un circuit. */
        boolean hasUser,
        /** Adresse de connexion, {@code null} si l'employé n'a pas de compte. */
        String email,
        /** Compte ouvert ou suspendu. */
        boolean compteActif,
        /** Dernière connexion réussie, {@code null} si jamais connecté. */
        Instant derniereConnexion,
        List<Ref> espacesProprietaire,
        List<Ref> groupesAcces,
        int documentsDeposes,
        int signaturesEnAttente,
        int signaturesTraitees
) {
    public record Ref(UUID id, String label) {}
}
