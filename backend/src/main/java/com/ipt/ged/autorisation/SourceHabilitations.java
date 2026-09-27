package com.ipt.ged.autorisation;

import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;

/**
 * <b>Point d'extension</b> des sujets et de leurs attributions (§12.2.1, §12.2.2).
 *
 * <p>Le résolveur de droits interroge toutes les sources déclarées comme beans :
 * <ol>
 *   <li>{@link #sujet} : la première source qui reconnaît l'appelant fournit le
 *       sujet (utilisateur authentifié par jeton, application par clé d'API…) ;</li>
 *   <li>{@link #attributions} : les attributions de TOUTES les sources sont
 *       réunies (union), puis résolues par la même règle — héritage, rupture,
 *       document isolé, accès global.</li>
 * </ol>
 *
 * <p>Livrée avec le lot E3 : {@link SourceHabilitationsUtilisateurs} (identités
 * et groupes GED, table {@code habilitation}). La portée des clés d'API (dev2,
 * vague 4) s'ajoute en déclarant une seconde source : elle reconnaît son
 * {@code Authentication} et traduit {@code cle_api_portee} en attributions
 * (liste d'opérations par espace) — ou pose simplement des lignes
 * {@code habilitation} de sujet APPLICATION. Aucun autre code n'est à modifier ;
 * toute modification de portée doit appeler
 * {@link VersionHabilitations#incrementer()} pour invalider le cache.
 */
public interface SourceHabilitations {

    /** Sujet correspondant à l'appelant, si cette source le reconnaît. */
    Optional<Sujet> sujet(Authentication authentification);

    /** Attributions applicables au sujet ; liste vide si la source n'est pas concernée. */
    List<Attribution> attributions(Sujet sujet);
}
