package com.ipt.ged.security;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CompteUtilisateurRepository extends JpaRepository<CompteUtilisateur, UUID> {

    /** L'e-mail est stocké en minuscules ; la recherche l'est aussi, pour que
     *  la casse saisie à la connexion n'ait aucune importance. */
    Optional<CompteUtilisateur> findByEmailIgnoreCase(String email);

    /** Le compte rattaché à un employé — la fiche de profil s'en sert pour
        afficher l'adresse de connexion et la dernière visite. */
    Optional<CompteUtilisateur> findByEmployeId(UUID employeId);

    boolean existsByEmailIgnoreCase(String email);
}
