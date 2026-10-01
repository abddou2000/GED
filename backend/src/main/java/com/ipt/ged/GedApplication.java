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

    /** Code de sortie d'un démarrage refusé (configuration invalide, dépendance absente). */
    static final int CODE_DEMARRAGE_REFUSE = 1;

    public static void main(String[] args) {
        try {
            SpringApplication.run(GedApplication.class, args);
        } catch (Throwable e) {
            // Spring Boot a déjà journalisé la cause (« Application run failed ») et
            // fermé le contexte. La JVM doit s'arrêter, avec un code non nul, même si
            // un fil non démon a survécu (O1, recette vague 9) : sous systemd, un
            // service bloqué dans cet état ne serait ni signalé ni redémarré.
            System.exit(CODE_DEMARRAGE_REFUSE);
        }
    }
}
