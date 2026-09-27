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
public record CriteresMetadonnees(UUID typeDocumentId, UUID workspaceId, LocalDate deposeDu, LocalDate deposeAu,
                                  Archives archives, String canalDepot) {

    public static final CriteresMetadonnees AUCUN = new CriteresMetadonnees(null, null, null, null);

    /**
     * Documents archivés (§12.6) : inclus par défaut dans la recherche, avec un
     * filtre pour les exclure ou ne garder qu'eux.
     */
    public enum Archives { INCLURE, EXCLURE, SEULEMENT }

    public CriteresMetadonnees(UUID typeDocumentId, UUID workspaceId, LocalDate deposeDu, LocalDate deposeAu) {
        this(typeDocumentId, workspaceId, deposeDu, deposeAu, Archives.INCLURE, null);
    }

    public CriteresMetadonnees(UUID typeDocumentId, UUID workspaceId, LocalDate deposeDu, LocalDate deposeAu,
                               Archives archives) {
        this(typeDocumentId, workspaceId, deposeDu, deposeAu, archives, null);
    }

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
            // Emplacement principal ou rattachement (§12.4).
            f.add(new FragmentSql("(d.noeud_principal_id = :critere_espace OR EXISTS (SELECT 1 FROM document_rattachement "
                    + "critere_r WHERE critere_r.document_id = d.id AND critere_r.noeud_id = :critere_espace))",
                    Map.of("critere_espace", workspaceId)));
        }
        if (deposeDu != null) {
            f.add(new FragmentSql("d.created_at >= :critere_du",
                    Map.of("critere_du", deposeDu.atStartOfDay().atOffset(ZoneOffset.UTC))));
        }
        if (deposeAu != null) {
            f.add(new FragmentSql("d.created_at < :critere_au",
                    Map.of("critere_au", deposeAu.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC))));
        }
        if (canalDepot != null && !canalDepot.isBlank()) {
            // Canal du dépôt (T-040) : INTERFACE, API, BUREAU_ORDRE, REPRISE.
            f.add(new FragmentSql("d.canal_depot = :critere_canal", Map.of("critere_canal",
                    com.ipt.ged.depot.source.CanalDepot.valueOf(canalDepot.trim().toUpperCase()).name())));
        }
        if (archives == Archives.EXCLURE) {
            f.add(new FragmentSql("d.statut_conservation <> 'ARCHIVE'", Map.of()));
        } else if (archives == Archives.SEULEMENT) {
            f.add(new FragmentSql("d.statut_conservation = 'ARCHIVE'", Map.of()));
        }
        return f;
    }
}
