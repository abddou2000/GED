-- =====================================================================
--  Reprise des données — étape 3 : contrôles de complétude et de cohérence
-- =====================================================================
--  À exécuter après 02_reprise.sql, même chemin de recherche (schéma cible).
--  Lecture seule. Chaque ligne compare une valeur attendue et une valeur
--  obtenue ; toute ligne dont le statut n'est pas OK doit être analysée avant
--  la mise en service.
-- =====================================================================

WITH controles(ordre, controle, attendu, obtenu) AS (
    -- 1. Complétude : autant de lignes de chaque côté.
              SELECT 1, 'lignes employe',                 (SELECT count(*) FROM reprise_source.employes),               (SELECT count(*) FROM employe)
    UNION ALL SELECT 3, 'lignes workflow_ged',            (SELECT count(*) FROM reprise_source.workflow_ged),           (SELECT count(*) FROM workflow_ged)
    UNION ALL SELECT 4, 'lignes workflow_ged_etape',      (SELECT count(*) FROM reprise_source.workflow_ged_steps),     (SELECT count(*) FROM workflow_ged_etape)
    UNION ALL SELECT 5, 'lignes noeud',                   (SELECT count(*) FROM reprise_source.work_spaces),            (SELECT count(*) FROM noeud)
    UNION ALL SELECT 6, 'lignes groupe_ged',              (SELECT count(*) FROM reprise_source.access_groups),          (SELECT count(*) FROM groupe_ged)
    UNION ALL SELECT 7, 'habilitations de groupe',        (SELECT count(*) FROM (SELECT DISTINCT access_group_id, workspace_id FROM reprise_source.pivot_workspace_groups) p),
                                                          (SELECT count(*) FROM habilitation WHERE sujet_type = 'GROUPE' AND noeud_id IS NOT NULL)
    UNION ALL SELECT 8, 'lignes groupe_membre',           (SELECT count(*) FROM reprise_source.pivot_employe_groups),   (SELECT count(*) FROM groupe_membre)
    UNION ALL SELECT 9, 'lignes etiquette',               (SELECT count(*) FROM reprise_source.etiquettes),             (SELECT count(*) FROM etiquette)
    UNION ALL SELECT 10, 'lignes index_def',              (SELECT count(*) FROM reprise_source.indices),                (SELECT count(*) FROM index_def)
    UNION ALL SELECT 11, 'lignes plan_indexation',        (SELECT count(*) FROM reprise_source.plan_d_indexations),     (SELECT count(*) FROM plan_indexation)
    UNION ALL SELECT 12, 'lignes plan_index',             (SELECT count(*) FROM reprise_source.pivot_plan_d_indexation_indices), (SELECT count(*) FROM plan_index)
    UNION ALL SELECT 13, 'lignes type_document',          (SELECT count(*) FROM reprise_source.type_de_documents),      (SELECT count(*) FROM type_document)
    UNION ALL SELECT 14, 'lignes document',               (SELECT count(*) FROM reprise_source.documents_file),         (SELECT count(*) FROM document)
    UNION ALL SELECT 15, 'lignes version_document',       (SELECT count(*) FROM reprise_source.document_versions),      (SELECT count(*) FROM version_document)
    UNION ALL SELECT 16, 'lignes document_etiquette',     (SELECT count(*) FROM reprise_source.pivot_document_etiquettes), (SELECT count(*) FROM document_etiquette)
    UNION ALL SELECT 17, 'lignes document_index_valeur',  (SELECT count(*) FROM reprise_source.document_index_values),  (SELECT count(*) FROM document_index_valeur)
    UNION ALL SELECT 18, 'lignes workflow_ged_signature', (SELECT count(*) FROM reprise_source.workflow_ged_signatures), (SELECT count(*) FROM workflow_ged_signature)

    -- 2. Références facultatives perdues : un ancien id qui ne désignait
    --    aucune ligne existante devient NULL sans bloquer la reprise.
    UNION ALL SELECT 20, 'créateur de document introuvable', 0::bigint,
        (SELECT count(*) FROM reprise_source.documents_file s
          WHERE s.created_by_employe_id IS NOT NULL
            AND reprise_source.nouvel_id('employes', s.created_by_employe_id) IS NULL)
    UNION ALL SELECT 21, 'dossier parent introuvable', 0::bigint,
        (SELECT count(*) FROM reprise_source.work_spaces s
          WHERE s.parent_workspace_id IS NOT NULL
            AND reprise_source.nouvel_id('work_spaces', s.parent_workspace_id) IS NULL)
    UNION ALL SELECT 22, 'plan d''indexation de type introuvable', 0::bigint,
        (SELECT count(*) FROM reprise_source.type_de_documents s
          WHERE s.plan_d_indexation_id IS NOT NULL
            AND reprise_source.nouvel_id('plan_d_indexations', s.plan_d_indexation_id) IS NULL)

    -- 3. Fidélité des valeurs sensibles.
    UNION ALL SELECT 30, 'chemins de fichier modifiés (document)', 0::bigint,
        (SELECT count(*) FROM reprise_source.documents_file s
           JOIN document d ON d.id = reprise_source.nouvel_id('documents_file', s.id)
          WHERE d.file_path IS DISTINCT FROM s.file_path)
    UNION ALL SELECT 31, 'chemins de fichier modifiés (version)', 0::bigint,
        (SELECT count(*) FROM reprise_source.document_versions s
           JOIN version_document v ON v.id = reprise_source.nouvel_id('document_versions', s.id)
          WHERE v.file_path IS DISTINCT FROM s.file_path)
    UNION ALL SELECT 32, 'noms de document modifiés', 0::bigint,
        (SELECT count(*) FROM reprise_source.documents_file s
           JOIN document d ON d.id = reprise_source.nouvel_id('documents_file', s.id)
          WHERE d.name IS DISTINCT FROM s.name)
    UNION ALL SELECT 33, 'dates de création décalées', 0::bigint,
        (SELECT count(*) FROM reprise_source.documents_file s
           JOIN document d ON d.id = reprise_source.nouvel_id('documents_file', s.id)
          WHERE d.created_at IS DISTINCT FROM s.created_at AT TIME ZONE 'UTC')
    UNION ALL SELECT 34, 'documents en corbeille', (SELECT count(*) FROM reprise_source.documents_file WHERE deleted),
        (SELECT count(*) FROM document WHERE supprime)
    UNION ALL SELECT 35, 'versions principales', (SELECT count(*) FROM reprise_source.document_versions WHERE is_default),
        (SELECT count(*) FROM version_document WHERE is_default)
    UNION ALL SELECT 36, 'jetons de charte encore numériques et désignant un index', 0::bigint,
        (SELECT count(*) FROM plan_indexation p,
                LATERAL jsonb_array_elements_text(
                    CASE WHEN p.charte_nommage IS JSON OBJECT
                          AND jsonb_typeof(p.charte_nommage::jsonb -> 'indexs') = 'array'
                         THEN p.charte_nommage::jsonb -> 'indexs' ELSE '[]'::jsonb END) AS j(jeton)
          WHERE j.jeton ~ '^[0-9]{1,18}$'
            AND EXISTS (SELECT 1 FROM reprise_source.indices i WHERE i.id = j.jeton::bigint))

    -- 4. Identifiants : tous des UUID v7, et une correspondance par ancienne ligne.
    UNION ALL SELECT 40, 'identifiants non UUID v7', 0::bigint,
        (SELECT count(*) FROM reprise_source.correspondance WHERE substr(id::text, 15, 1) <> '7')
    UNION ALL SELECT 41, 'correspondances enregistrées',
        (SELECT (SELECT count(*) FROM reprise_source.employes)
              + (SELECT count(*) FROM reprise_source.workflow_ged) + (SELECT count(*) FROM reprise_source.workflow_ged_steps)
              + (SELECT count(*) FROM reprise_source.work_spaces) + (SELECT count(*) FROM reprise_source.access_groups)
              + (SELECT count(*) FROM reprise_source.etiquettes) + (SELECT count(*) FROM reprise_source.indices)
              + (SELECT count(*) FROM reprise_source.plan_d_indexations) + (SELECT count(*) FROM reprise_source.type_de_documents)
              + (SELECT count(*) FROM reprise_source.documents_file) + (SELECT count(*) FROM reprise_source.document_versions)
              + (SELECT count(*) FROM reprise_source.document_index_values) + (SELECT count(*) FROM reprise_source.workflow_ged_signatures)),
        (SELECT count(*) FROM reprise_source.correspondance)
)
SELECT ordre, controle, attendu, obtenu,
       CASE WHEN attendu = obtenu THEN 'OK' ELSE 'ECART' END AS statut
  FROM controles
 ORDER BY ordre;
