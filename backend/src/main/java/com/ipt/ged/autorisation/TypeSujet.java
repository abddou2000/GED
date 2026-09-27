package com.ipt.ged.autorisation;

/** Nature du sujet d'une habilitation (§12.2.1). */
public enum TypeSujet {
    /** Une identité GED (personne de l'annuaire). */
    UTILISATEUR,
    /** Un groupe interne à la GED : ses membres en héritent. */
    GROUPE,
    /** Une application appelante (clé d'API, lot E9) : un sujet comme un autre. */
    APPLICATION
}
