package com.ipt.ged.common.erreur;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Corps de requête dont un champ inconnu est <b>refusé</b> (400
 * {@link CodesErreur#PARAMETRE_INCONNU}) au lieu d'être ignoré, comme le fait
 * Jackson par défaut dans l'application.
 *
 * <p>À poser sur les corps de recherche : un critère mal nommé
 * ({@code "confidentialit"}) ignoré en silence rend un résultat non filtré que
 * l'appelant prend pour filtré (ANO-F-011). Porté par la classe du corps et par
 * celles de ses éléments imbriqués (critères). Mis en œuvre par
 * {@link ModuleChampsInconnus}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ChampsInconnusRefuses {
}
