package com.ipt.ged.modules;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * État des modules métier (T-088) lu au démarrage : {@code ged.modules.<code>.actif},
 * vrai par défaut. Un code inconnu fait échouer le démarrage : une faute de
 * frappe ne doit pas laisser croire qu'un module est fermé alors qu'il est ouvert.
 */
@Component
public class ModulesActifs {

    private final Map<ModuleMetier, Boolean> etats = new EnumMap<>(ModuleMetier.class);
    private final AntPathMatcher chemins = new AntPathMatcher();

    public ModulesActifs(Environment environnement) {
        Map<String, Object> declares = Binder.get(environnement)
                .bind("ged.modules", Bindable.mapOf(String.class, Object.class)).orElse(Map.of());
        Set<String> inconnus = new TreeSet<>();
        for (String code : declares.keySet()) {
            if (ModuleMetier.parCode(code) == null) inconnus.add(code);
        }
        if (!inconnus.isEmpty()) {
            throw new IllegalStateException("Module(s) inconnu(s) dans ged.modules : " + inconnus
                    + " ; modules connus : " + List.of(ModuleMetier.values()).stream().map(ModuleMetier::code).toList());
        }
        for (ModuleMetier m : ModuleMetier.values()) {
            etats.put(m, environnement.getProperty("ged.modules." + m.code() + ".actif", Boolean.class, Boolean.TRUE));
        }
    }

    public boolean actif(ModuleMetier module) {
        return etats.get(module);
    }

    /** Module inactif auquel appartient ce chemin, s'il y en a un. */
    public Optional<ModuleMetier> inactifPour(String chemin) {
        for (Map.Entry<ModuleMetier, Boolean> e : etats.entrySet()) {
            if (e.getValue()) continue;
            for (String motif : e.getKey().routes()) {
                if (chemins.match(motif, chemin)) return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
    }

    public Map<ModuleMetier, Boolean> etats() {
        return Map.copyOf(etats);
    }
}
