package com.ipt.ged.typedocument;

/** Point de départ de la durée de conservation d'un type de document (§12.9). */
public enum PointDepart {
    /** Date du document (défaut). */
    DATE_DOCUMENT,
    /** Date de dépôt dans la GED. */
    DATE_DEPOT,
    /** Une métadonnée de nature date du plan du type ({@code point_depart_index_code}). */
    METADONNEE
}
