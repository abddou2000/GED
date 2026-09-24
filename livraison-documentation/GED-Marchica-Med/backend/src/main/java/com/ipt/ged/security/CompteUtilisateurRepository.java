package com.ipt.ged.security;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompteUtilisateurRepository extends JpaRepository<CompteUtilisateur, Long> {

    /** L'e-mail est stocké en minuscules ; la recherche l'est aussi, pour que
     *  la casse saisie à la connexion n'ait aucune importance. */
    Optional<CompteUtilisateur> findByEmailIgnoreCase(String email);

    /** Le compte rattaché à un employé — la fiche de profil s'en sert pour
        afficher l'adresse de connexion et la dernière visite. */
    Optional<CompteUtilisateur> findByEmployeId(Long employeId);

    boolean existsByEmailIgnoreCase(String email);
}
