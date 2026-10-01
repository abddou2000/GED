package com.ipt.ged.common.erreur;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Corps de requête dont un champ inconnu est <b>ignoré et signalé</b> : la
 * requête est servie comme sans ce champ (politique de compatibilité du DAT
 * §5.3.2, P-08 : évolutions additives, champs inconnus ignorés par le serveur),
 * et la réponse porte l'en-tête {@value ChampsIgnores#ENTETE} qui en donne le
 * chemin.
 *
 * <p>À poser sur les corps de recherche : un critère mal nommé
 * ({@code "confidentialit"}) rend un résultat non filtré ; l'en-tête permet à
 * l'appelant de le voir au lieu de le prendre pour filtré (ANO-F-011). Porté
 * par la classe du corps et par celles de ses éléments imbriqués (critères).
 * Mis en œuvre par {@link ModuleChampsInconnus}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ChampsInconnusSignales {
}
