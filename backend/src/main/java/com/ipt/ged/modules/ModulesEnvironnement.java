package com.ipt.ged.modules;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Arrêt des traitements de fond d'un module désactivé (T-088, DAT §9.3).
 *
 * <p>Chaque module déclare les propriétés techniques qui arrêtent ses
 * traitements (chaîne OCR, alertes d'échéance, expédition des e-mails…) ; si
 * {@code ged.modules.<code>.actif} vaut {@code false}, ces propriétés sont
 * posées en tête de l'environnement, avant la création des beans : le drapeau
 * du module l'emporte sur tout réglage fin laissé actif par mégarde. Un module
 * actif ne change rien : ses propres réglages s'appliquent.
 */
public class ModulesEnvironnement implements EnvironmentPostProcessor {

    /** Nom de la source de propriétés ajoutée (visible dans /actuator/env). */
    public static final String SOURCE = "ged-modules-inactifs";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environnement, SpringApplication application) {
        Map<String, Object> imposees = new LinkedHashMap<>();
        for (ModuleMetier m : ModuleMetier.values()) {
            if (!actif(environnement, m)) imposees.putAll(m.proprietesArret());
        }
        if (!imposees.isEmpty()) {
            environnement.getPropertySources().addFirst(new MapPropertySource(SOURCE, imposees));
        }
    }

    static boolean actif(ConfigurableEnvironment environnement, ModuleMetier m) {
        return environnement.getProperty("ged.modules." + m.code() + ".actif", Boolean.class, Boolean.TRUE);
    }
}
