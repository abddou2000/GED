package com.ipt.ged.autorisation;

import java.util.EnumSet;
import java.util.Set;

/**
 * Permissions livrées par la GED (dossier technique §12.2.1, décision D14).
 *
 * <p>Liste CLOSE : les rôles se composent librement depuis l'interface, mais
 * les permissions sont celles que le code sait appliquer. La table
 * {@code permission} en porte exactement les mêmes codes (changeset
 * 202609281010-2) ; un test vérifie la correspondance.
 */
public enum CodePermission {

    // Permissions élémentaires, appliquées sur les nœuds et les documents.
    CONSULTER(Categorie.ELEMENTAIRE),
    DEPOSER(Categorie.ELEMENTAIRE),
    MODIFIER(Categorie.ELEMENTAIRE),
    VALIDER(Categorie.ELEMENTAIRE),
    DIFFUSER(Categorie.ELEMENTAIRE),
    DEPLACER(Categorie.ELEMENTAIRE),
    ARCHIVER(Categorie.ELEMENTAIRE),
    SUPPRIMER(Categorie.ELEMENTAIRE),
    PURGER(Categorie.ELEMENTAIRE),

    // Permissions d'administration, distinctes : elles ne s'exercent qu'en
    // portée globale (habilitation sans cible).
    GERER_REFERENTIELS(Categorie.ADMINISTRATION),
    GERER_ESPACES(Categorie.ADMINISTRATION),
    GERER_ROLES_HABILITATIONS(Categorie.ADMINISTRATION),
    GERER_CLES_API(Categorie.ADMINISTRATION),
    CONSULTER_AUDIT(Categorie.ADMINISTRATION),
    ADMINISTRER_INDEX(Categorie.ADMINISTRATION),
    SUPERVISER_TRAITEMENTS(Categorie.ADMINISTRATION),

    // Permissions techniques de confidentialité (§12.3).
    VOIR_PRIVE(Categorie.CONFIDENTIALITE),
    VOIR_CONFIDENTIEL(Categorie.CONFIDENTIALITE);

    public enum Categorie { ELEMENTAIRE, ADMINISTRATION, CONFIDENTIALITE }

    private final Categorie categorie;

    CodePermission(Categorie categorie) {
        this.categorie = categorie;
    }

    public Categorie categorie() {
        return categorie;
    }

    public static Set<CodePermission> de(Categorie c) {
        EnumSet<CodePermission> s = EnumSet.noneOf(CodePermission.class);
        for (CodePermission p : values()) {
            if (p.categorie == c) s.add(p);
        }
        return s;
    }

    /** Code inconnu (permission ajoutée en base sans le code correspondant) : ignoré. */
    public static CodePermission depuis(String code) {
        try {
            return valueOf(code);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
