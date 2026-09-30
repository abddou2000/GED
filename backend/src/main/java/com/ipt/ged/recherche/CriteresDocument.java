package com.ipt.ged.recherche;

import com.ipt.ged.autorisation.Confidentialite;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Critères imposés du §4.4.3 portés par le document lui-même, communs aux
 * trois recherches ({@code POST /documents/recherche}, {@code POST /recherches},
 * {@code GET /recherche/plein-texte}) — ANO-F-011 :
 *
 * <ul>
 *   <li>plage de <b>date du document</b> (bornes incluses), clé de tri
 *       prioritaire (§12.7), servie par {@code idx_document_date_document} ;</li>
 *   <li><b>niveau de confidentialité</b> (§12.3) : ne fait que restreindre le
 *       périmètre, qui reste celui du prédicat de droits (un document
 *       confidentiel non désigné n'apparaît pas davantage) ;</li>
 *   <li><b>déposant</b> : identité GED de la personne qui a déposé (utilisateur,
 *       ou personne pour le compte de laquelle une application a déposé, §5.5).</li>
 * </ul>
 *
 * Traduits en {@link FragmentSql} paramétrés sur l'alias {@code d} (document).
 */
public record CriteresDocument(LocalDate dateDocumentDu, LocalDate dateDocumentAu,
                               Confidentialite confidentialite, UUID deposantUtilisateurId) {

    public static final CriteresDocument AUCUN = new CriteresDocument(null, null, null, null);

    public CriteresDocument {
        if (dateDocumentDu != null && dateDocumentAu != null && dateDocumentDu.isAfter(dateDocumentAu)) {
            throw new IllegalArgumentException("Date du document : la date de début est postérieure à la date de fin.");
        }
    }

    public List<FragmentSql> fragments() {
        List<FragmentSql> f = new ArrayList<>();
        if (dateDocumentDu != null) {
            f.add(new FragmentSql("d.date_document >= :critere_date_document_du",
                    Map.of("critere_date_document_du", dateDocumentDu)));
        }
        if (dateDocumentAu != null) {
            f.add(new FragmentSql("d.date_document <= :critere_date_document_au",
                    Map.of("critere_date_document_au", dateDocumentAu)));
        }
        if (confidentialite != null) {
            f.add(new FragmentSql("d.confidentialite = :critere_confidentialite",
                    Map.of("critere_confidentialite", confidentialite.name())));
        }
        if (deposantUtilisateurId != null) {
            f.add(new FragmentSql("d.deposant_utilisateur_id = :critere_deposant",
                    Map.of("critere_deposant", deposantUtilisateurId)));
        }
        return f;
    }
}
