package com.ipt.ged.recherche;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Critères multicritères sur les métadonnées, combinés en ET avec le plein
 * texte (§4.4). Traduits en {@link FragmentSql} paramétrés sur l'alias
 * {@code d} (document) de la requête de recherche.
 *
 * @param deposeDu borne basse incluse de la date de dépôt ;
 * @param deposeAu borne haute incluse de la date de dépôt.
 */
public record CriteresMetadonnees(UUID typeDocumentId, UUID workspaceId, LocalDate deposeDu, LocalDate deposeAu) {

    public static final CriteresMetadonnees AUCUN = new CriteresMetadonnees(null, null, null, null);

    public CriteresMetadonnees {
        if (deposeDu != null && deposeAu != null && deposeDu.isAfter(deposeAu)) {
            throw new IllegalArgumentException("La date de début est postérieure à la date de fin.");
        }
    }

    public List<FragmentSql> fragments() {
        List<FragmentSql> f = new ArrayList<>();
        if (typeDocumentId != null) {
            f.add(new FragmentSql("d.type_document_id = :critere_type", Map.of("critere_type", typeDocumentId)));
        }
        if (workspaceId != null) {
            f.add(new FragmentSql("d.workspace_id = :critere_espace", Map.of("critere_espace", workspaceId)));
        }
        if (deposeDu != null) {
            f.add(new FragmentSql("d.created_at >= :critere_du",
                    Map.of("critere_du", deposeDu.atStartOfDay().atOffset(ZoneOffset.UTC))));
        }
        if (deposeAu != null) {
            f.add(new FragmentSql("d.created_at < :critere_au",
                    Map.of("critere_au", deposeAu.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC))));
        }
        return f;
    }
}
