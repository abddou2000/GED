package com.ipt.ged.documentationapi;

import io.swagger.v3.core.jackson.TypeNameResolver;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Noms des schémas OpenAPI sans collision, et <b>stables</b>.
 *
 * <p>springdoc nomme un schéma par le nom simple de la classe : deux charges
 * utiles de lots différents qui s'appellent pareil ({@code Resultat} de
 * l'archivage et de la recherche, les {@code Ref} de chaque réponse) seraient
 * FUSIONNÉES en un seul schéma, faux pour l'une des deux.
 *
 * <p>Les noms simples portés par plusieurs classes de l'application sont
 * recensés une fois (parcours du paquet {@code com.ipt.ged}) ; chacune de ces
 * classes reçoit alors le nom de sa classe englobante en préfixe
 * ({@code PageResultatsResultat}, {@code ArchivageServiceResultat}), ou de son
 * paquet pour une classe de premier niveau. Un nom unique reste simple. Le
 * résultat ne dépend donc pas de l'ordre dans lequel springdoc rencontre les
 * types. Aucun DTO des autres lots n'est renommé.
 */
public class NomsSchemasDistincts extends TypeNameResolver {

    private final Set<String> ambigus;
    private final Map<Class<?>, String> noms = new ConcurrentHashMap<>();

    public NomsSchemasDistincts() {
        this(nomsAmbigus("com.ipt.ged"));
    }

    NomsSchemasDistincts(Set<String> ambigus) {
        this.ambigus = Set.copyOf(ambigus);
    }

    /** Noms simples portés par au moins deux classes du paquet. */
    static Set<String> nomsAmbigus(String paquet) {
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return true;
            }
        };
        scan.addIncludeFilter((lecteur, fabrique) -> true);
        Map<String, Integer> compte = new HashMap<>();
        for (BeanDefinition d : scan.findCandidateComponents(paquet)) {
            String nom = d.getBeanClassName();
            if (nom == null) continue;
            String simple = nom.substring(Math.max(nom.lastIndexOf('.'), nom.lastIndexOf('$')) + 1);
            compte.merge(simple, 1, Integer::sum);
        }
        return compte.entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    @Override
    protected String getNameOfClass(Class<?> classe) {
        return noms.computeIfAbsent(classe, c -> {
            String simple = super.getNameOfClass(c);
            if (!c.getName().startsWith("com.ipt.ged.") || !ambigus.contains(c.getSimpleName())) return simple;
            Class<?> englobante = c.getEnclosingClass();
            if (englobante != null) return englobante.getSimpleName() + simple;
            String paquet = c.getPackageName().substring(c.getPackageName().lastIndexOf('.') + 1);
            return Character.toUpperCase(paquet.charAt(0)) + paquet.substring(1) + simple;
        });
    }
}
