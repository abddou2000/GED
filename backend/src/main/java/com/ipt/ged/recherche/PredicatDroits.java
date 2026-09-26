package com.ipt.ged.recherche;

import org.springframework.security.core.Authentication;

/**
 * <b>Point d'extension du lot autorisation (dev1)</b> : filtrage par droits
 * <i>à la source</i> (principe P5, §4.4).
 *
 * <p>Le prédicat est évalué dans la requête même de recherche : le total, le
 * tri et la pagination ne portent que sur le périmètre autorisé, et les
 * extraits {@code ts_headline} ne sont calculés que pour les lignes autorisées
 * de la page affichée. Un document rattaché à plusieurs espaces est testé par
 * {@code EXISTS} sur ses emplacements : il ne sort qu'une fois.
 *
 * <p>Implémentation attendue du lot autorisation, par exemple :
 * <pre>
 * EXISTS (SELECT 1 FROM emplacement_document e
 *         WHERE e.document_id = dt.document_id
 *           AND e.noeud_id IN (:droits_noeuds_lisibles))
 * AND (SELECT d.niveau_confidentialite FROM document d WHERE d.id = dt.document_id) &lt;= :droits_niveau
 * </pre>
 */
public interface PredicatDroits {

    /**
     * @param colonneDocumentId expression SQL de l'identifiant du document
     *                          dans la requête (ex. {@code dt.document_id}).
     */
    FragmentSql predicat(String colonneDocumentId, Authentication utilisateur);
}
