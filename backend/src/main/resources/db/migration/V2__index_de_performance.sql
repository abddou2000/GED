-- =====================================================================
--  Index de performance
-- =====================================================================
--  POURQUOI CE FICHIER EXISTE
--  Le schema issu des entites ne portait QUE les cles primaires, les cles
--  etrangeres et les contraintes d'unicite. Aucune des colonnes reellement
--  interrogees n'etait indexee — a commencer par `deleted`, present dans le
--  WHERE de TOUTES les listes de l'application, et par les colonnes de tri.
--
--  Sur le jeu de developpement (quelques dizaines de lignes) la difference ne
--  se voit pas. Elle se voit sur une GED reelle, ou les documents se comptent
--  en dizaines de milliers : sans ces index, chaque affichage de liste balaie
--  la table entiere.
--
--  Un index se justifie par un acces, pas par principe : chacun ci-dessous
--  correspond a une requete existante du code, notee en commentaire.
-- =====================================================================

-- Corbeille : filtre de toutes les listes (`findByDeletedFalse...`).
CREATE INDEX idx_documents_deleted        ON documents_file (deleted);
CREATE INDEX idx_workspaces_deleted       ON work_spaces (deleted);
CREATE INDEX idx_types_deleted            ON type_de_documents (deleted);
CREATE INDEX idx_indices_deleted          ON indices (deleted);
CREATE INDEX idx_plans_deleted            ON plan_d_indexations (deleted);
CREATE INDEX idx_groupes_deleted          ON access_groups (deleted);
CREATE INDEX idx_etiquettes_deleted       ON etiquettes (deleted);
CREATE INDEX idx_workflows_deleted        ON workflow_ged (deleted);

-- Tri par defaut de la liste des documents (`ORDER BY created_at DESC`),
-- et tri par nom, qui est le plus utilise apres lui.
CREATE INDEX idx_documents_created_at     ON documents_file (created_at);
CREATE INDEX idx_documents_name           ON documents_file (name);

-- Filtre « documents d'un dossier » : l'arbre des espaces l'appelle a chaque
-- depliage, et la fiche d'un espace a chaque ouverture.
CREATE INDEX idx_documents_workspace      ON documents_file (workspace_id);

-- Ecran « Mes workflow » et compteur du menu : les etapes a traiter d'une
-- personne. Index composite, car les deux colonnes sont toujours filtrees
-- ensemble (`findByEmployeIdAndStatus...`).
CREATE INDEX idx_signatures_employe_statut ON workflow_ged_signatures (employe_id, status);

-- Circuit d'un document : lu a chaque ouverture de fiche, et a chaque
-- approbation pour verifier l'etape precedente.
CREATE INDEX idx_signatures_document      ON workflow_ged_signatures (document_id);

-- Recherche par index : jointure valeurs -> documents, et filtre par index.
CREATE INDEX idx_valeurs_index_champ      ON document_index_values (index_field_id);

-- Echeances : la colonne est affichee et triable dans la liste des documents.
CREATE INDEX idx_documents_expiration     ON documents_file (expiration_date);
