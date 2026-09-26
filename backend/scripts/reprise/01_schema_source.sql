-- =====================================================================
--  Reprise des données — étape 1 : schéma de transit `reprise_source`
-- =====================================================================
--  Reçoit, tel quel, le contenu de l'ANCIEN schéma MySQL (identifiants
--  numériques) exporté par exporter-mysql.sh. Les colonnes reprennent les
--  noms et l'ordre de l'ancien schéma (reference/ancien-schema-mysql.sql) ;
--  seuls les types sont traduits en PostgreSQL :
--    bigint auto_increment -> bigint      bit / boolean -> boolean
--    datetime(6) (UTC)     -> timestamp   enum(...)     -> text
--  Aucune contrainte : le transit accepte tout, les contrôles viennent après
--  (05_controles.sql). Les horodatages sont en UTC, comme l'ancienne
--  application les écrivait (serverTimezone=UTC) ; la conversion en
--  timestamptz se fait à la reprise.
--
--  Les comptes locaux (comptes_utilisateurs, empreintes BCrypt) ne sont PAS
--  repris : depuis le lot E2 l'authentification passe par l'annuaire, et la GED
--  ne conserve aucun mot de passe (dossier technique §3.2). Chaque personne
--  retrouve sa fiche employé à sa première connexion par l'annuaire
--  (rapprochement par courriel, voir ServiceIdentites).
--
--  Exécuté par ged_owner (droit CREATE sur la base). Rejouable : le schéma est
--  recréé à vide.
-- =====================================================================

DROP SCHEMA IF EXISTS reprise_source CASCADE;
CREATE SCHEMA reprise_source;

CREATE TABLE reprise_source.employes (
    id bigint, first_name text, last_name text, has_user boolean,
    created_at timestamp, updated_at timestamp);

CREATE TABLE reprise_source.workflow_ged (
    id bigint, name text, deleted boolean, created_at timestamp, updated_at timestamp);

CREATE TABLE reprise_source.workflow_ged_steps (
    id bigint, workflow_ged_id bigint, employe_id bigint, label text, step_order integer,
    created_at timestamp, updated_at timestamp);

CREATE TABLE reprise_source.work_spaces (
    id bigint, name text, code text, description text, status text, employe_id bigint,
    parent_workspace_id bigint, workflow_ged_id bigint, deleted boolean,
    created_at timestamp, updated_at timestamp);

CREATE TABLE reprise_source.access_groups (
    id bigint, code text, name text,
    droit_access boolean, droit_lecture boolean, droit_modifier boolean, droit_uploader boolean,
    droit_supprimer boolean, droit_deplacer boolean, droit_ajouter_version boolean,
    droit_verrouiller_deverrouiller boolean, deleted boolean,
    created_at timestamp, updated_at timestamp);

CREATE TABLE reprise_source.pivot_workspace_groups (access_group_id bigint, workspace_id bigint);

CREATE TABLE reprise_source.pivot_employe_groups (access_group_id bigint, employe_id bigint);

CREATE TABLE reprise_source.etiquettes (
    id bigint, code text, tag text, couleur text, deleted boolean,
    created_at timestamp, updated_at timestamp);

CREATE TABLE reprise_source.indices (
    id bigint, code text, nom_index text, type_champs text, valeurs text, valeur_par_defaut text,
    obligatoire boolean, indexe_pour_recherche boolean, index_de_groupage boolean, deleted boolean,
    created_at timestamp, updated_at timestamp);

CREATE TABLE reprise_source.plan_d_indexations (
    id bigint, code text, nom_du_plan text, mode_indexation boolean, manuel boolean,
    majuscule boolean, separateur text, charte_nommage text, deleted boolean,
    created_at timestamp, updated_at timestamp);

CREATE TABLE reprise_source.pivot_plan_d_indexation_indices (
    plan_d_indexation_id bigint, index_id bigint, position integer);

CREATE TABLE reprise_source.type_de_documents (
    id bigint, code text, type_de_document text, description text, workspace_id bigint,
    plan_d_indexation_id bigint, type_autorise text, taille_max_mo integer, deleted boolean,
    created_at timestamp, updated_at timestamp);

CREATE TABLE reprise_source.documents_file (
    id bigint, name text, workspace_id bigint, type_document_id bigint, file_name text,
    file_path text, extension text, size_ko bigint, expiration_date date, reference text,
    active boolean, is_locked boolean, created_by_employe_id bigint, deleted boolean,
    created_at timestamp, updated_at timestamp);

CREATE TABLE reprise_source.document_versions (
    id bigint, document_id bigint, file_name text, file_path text, extension text,
    size_ko bigint, observation text, is_default boolean,
    created_at timestamp, updated_at timestamp);

CREATE TABLE reprise_source.pivot_document_etiquettes (document_id bigint, etiquette_id bigint);

CREATE TABLE reprise_source.document_index_values (
    id bigint, document_id bigint, index_field_id bigint, valeur text,
    created_at timestamp, updated_at timestamp);

CREATE TABLE reprise_source.workflow_ged_signatures (
    id bigint, document_id bigint, employe_id bigint, step_label text, step_order integer,
    status text, signed_at timestamp, motif text, created_at timestamp, updated_at timestamp);
