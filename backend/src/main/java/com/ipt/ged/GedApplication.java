package com.ipt.ged;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.ldap.LdapAutoConfiguration;

/**
 * Point d'entrée de l'application GED (Gestion Électronique de Documents).
 * Backend Spring Boot — API REST consommée par le frontend Angular.
 */
// La liaison à l'annuaire est construite par identite.annuaire.ConfigurationAnnuaire
// (une source par contrôleur, bascule D4) : pas de source LDAP par défaut de Spring Boot.
@SpringBootApplication(exclude = LdapAutoConfiguration.class)
public class GedApplication {

    public static void main(String[] args) {
        SpringApplication.run(GedApplication.class, args);
    }
}
