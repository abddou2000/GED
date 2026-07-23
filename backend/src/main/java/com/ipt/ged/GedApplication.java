package com.ipt.ged;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Point d'entrée de l'application GED (Gestion Électronique de Documents).
 * Backend Spring Boot — API REST consommée par le frontend Angular.
 */
@SpringBootApplication
public class GedApplication {

    public static void main(String[] args) {
        SpringApplication.run(GedApplication.class, args);
    }
}
