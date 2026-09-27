-- =====================================================================
--  Reprise des données — étape 2 : transfert vers le schéma cible (UUID)
-- =====================================================================
--  Lit `reprise_source` (ancien modèle, identifiants numériques) et remplit le
--  schéma cible créé par Liquibase (nouveau modèle, clés UUID v7).
--
--  Conditions d'exécution (voir LISEZ-MOI de la reprise dans DEPLOIEMENT.md) :
--    - le schéma cible existe, créé par Liquibase, et il est VIDE (vérifié) ;
--    - le chemin de recherche désigne le schéma cible : les tables cibles ne
--      sont pas qualifiées (PGOPTIONS='-c search_path=ged' avec psql) ;
--    - exécution en UNE transaction (`psql -1`) : tout passe ou rien ;
--    - compte ged_owner (création des fonctions de transit).
--
--  Choix de conversion :
--    - chaque ancienne ligne reçoit un UUID v7 dont l'horodatage est son
--      created_at (UTC) ; à horodatage égal, l'ancien identifiant départage.
--      L'ordre « par id » de l'application reste donc l'ordre chronologique ;
--    - la table reprise_source.correspondance conserve le lien ancien id ->
--      nouvel UUID : à exporter et archiver avant de supprimer le transit
--      (références externes, journaux, dossiers de fichiers numérotés) ;
--    - les horodatages MySQL, écrits en UTC, deviennent des timestamptz ;
--    - les jetons d'index de la charte de nommage (identifiants numériques
--      dans le JSON) sont traduits en UUID ;
--    - les chemins de fichiers (file_path) sont repris tels quels : les
--      fichiers restent là où ils sont sur le disque ;
--    - supprime_par et supprime_le restent vides : l'ancien modèle ne savait
--      ni qui ni quand ;
--    - document.metadonnees prend sa valeur par défaut ({}).
-- =====================================================================

-- ---------- 0. Garde-fou : la cible doit être vide ----------
DO $$
DECLARE
    t text;
    n bigint;
BEGIN
    FOREACH t IN ARRAY ARRAY['employe', 'utilisateur', 'workflow_ged', 'workflow_ged_etape',
        'noeud', 'groupe_ged', 'habilitation', 'groupe_membre', 'etiquette',
        'index_def', 'plan_indexation', 'plan_index', 'type_document', 'document', 'version_document',
        'document_etiquette', 'document_index_valeur', 'workflow_ged_signature']
    LOOP
        EXECUTE format('SELECT count(*) FROM %I', t) INTO n;
        IF n > 0 THEN
            RAISE EXCEPTION 'Reprise refusée : la table cible % contient déjà % ligne(s) (schéma %).',
                t, n, current_schema();
        END IF;
    END LOOP;
END
$$;

-- ---------- 1. Outillage de transit ----------

-- UUID v7 (RFC 9562) : 48 bits d'horodatage en ms, version 7, 12 bits de rang
-- (ordre à horodatage égal), 62 bits aléatoires avec la variante RFC.
CREATE OR REPLACE FUNCTION reprise_source.uuid_v7(horodatage timestamptz, rang bigint)
RETURNS uuid LANGUAGE sql VOLATILE AS $$
    SELECT (lpad(to_hex(floor(extract(epoch FROM horodatage) * 1000)::bigint + rang / 4096), 12, '0')
            || '7' || lpad(to_hex((rang % 4096)::int), 3, '0')
            || substr(replace(gen_random_uuid()::text, '-', ''), 17, 16))::uuid
$$;

CREATE TABLE reprise_source.correspondance (
    table_source text   NOT NULL,
    ancien_id    bigint NOT NULL,
    id           uuid   NOT NULL UNIQUE,
    PRIMARY KEY (table_source, ancien_id)
);

-- Attribue un UUID à chaque ligne d'une table source, dans l'ordre
-- (horodatage, ancien id). Une ligne sans horodatage prend une date fixe
-- antérieure à toute donnée réelle : elle reste avant les autres.
CREATE OR REPLACE FUNCTION reprise_source.correspondre(table_source text, colonne_horodatage text)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE format($f$
        INSERT INTO reprise_source.correspondance (table_source, ancien_id, id)
        SELECT %L, id,
               reprise_source.uuid_v7(h AT TIME ZONE 'UTC',
                                      row_number() OVER (PARTITION BY date_trunc('milliseconds', h) ORDER BY id) - 1)
          FROM (SELECT id, coalesce(%s, TIMESTAMP '2000-01-01 00:00:00') AS h
                  FROM reprise_source.%I) s
    $f$, table_source, colonne_horodatage, table_source);
END
$$;

-- Nouvel identifiant d'une ancienne ligne ; NULL si l'ancien id est NULL.
CREATE OR REPLACE FUNCTION reprise_source.nouvel_id(table_source text, ancien bigint)
RETURNS uuid LANGUAGE sql STABLE AS $$
    SELECT c.id FROM reprise_source.correspondance c
     WHERE c.table_source = $1 AND c.ancien_id = $2
$$;

-- Horodatage UTC de l'ancien modèle -> timestamptz.
CREATE OR REPLACE FUNCTION reprise_source.utc(t timestamp)
RETURNS timestamptz LANGUAGE sql IMMUTABLE AS $$
    SELECT t AT TIME ZONE 'UTC'
$$;

DO $$
BEGIN
    PERFORM reprise_source.correspondre('employes', 'created_at');
    PERFORM reprise_source.correspondre('workflow_ged', 'created_at');
    PERFORM reprise_source.correspondre('workflow_ged_steps', 'created_at');
    PERFORM reprise_source.correspondre('work_spaces', 'created_at');
    PERFORM reprise_source.correspondre('access_groups', 'created_at');
    PERFORM reprise_source.correspondre('etiquettes', 'created_at');
    PERFORM reprise_source.correspondre('indices', 'created_at');
    PERFORM reprise_source.correspondre('plan_d_indexations', 'created_at');
    PERFORM reprise_source.correspondre('type_de_documents', 'created_at');
    PERFORM reprise_source.correspondre('documents_file', 'created_at');
    PERFORM reprise_source.correspondre('document_versions', 'created_at');
    PERFORM reprise_source.correspondre('document_index_values', 'created_at');
    PERFORM reprise_source.correspondre('workflow_ged_signatures', 'created_at');
END
$$;

-- ---------- 2. Transfert, dans l'ordre des clés étrangères ----------
-- Une clé étrangère obligatoire sans correspondance (ligne orpheline dans
-- l'ancienne base) produit un NULL, refusé par la contrainte NOT NULL : la
-- transaction entière est annulée et l'erreur désigne la table.

INSERT INTO employe (id, first_name, last_name, has_user, created_at, updated_at)
SELECT reprise_source.nouvel_id('employes', s.id), s.first_name, s.last_name, coalesce(s.has_user, false),
       reprise_source.utc(s.created_at), reprise_source.utc(s.updated_at)
  FROM reprise_source.employes s;

-- Comptes locaux et empreintes de mot de passe : non repris (lot E2, §3.2).
-- Les identités GED (table utilisateur) naissent à la première connexion par
-- l'annuaire, rattachées à la fiche employé reprise ci-dessus.

INSERT INTO workflow_ged (id, name, supprime, created_at, updated_at)
SELECT reprise_source.nouvel_id('workflow_ged', s.id), s.name, coalesce(s.deleted, false),
       reprise_source.utc(s.created_at), reprise_source.utc(s.updated_at)
  FROM reprise_source.workflow_ged s;

INSERT INTO workflow_ged_etape (id, workflow_ged_id, employe_id, label, step_order, created_at, updated_at)
SELECT reprise_source.nouvel_id('workflow_ged_steps', s.id),
       reprise_source.nouvel_id('workflow_ged', s.workflow_ged_id),
       reprise_source.nouvel_id('employes', s.employe_id), s.label, s.step_order,
       reprise_source.utc(s.created_at), reprise_source.utc(s.updated_at)
  FROM reprise_source.workflow_ged_steps s;

-- Nœuds (ex-work_spaces, lot E3) : le chemin matérialisé d'un nœud est
-- calculé par la base à partir de celui de son parent (déclencheur
-- trg_noeud_chemin) ; les parents sont donc insérés avant leurs enfants,
-- niveau par niveau, quel que soit l'ordre des identifiants de la source.
-- Un nœud dont le parent est introuvable n'est pas repris : le contrôle 5
-- (lignes noeud) et le contrôle 21 le signalent.
INSERT INTO noeud (id, name, code, description, status, employe_id, parent_id, workflow_ged_id,
                   supprime, created_at, updated_at)
SELECT reprise_source.nouvel_id('work_spaces', s.id), s.name, s.code, s.description, s.status,
       reprise_source.nouvel_id('employes', s.employe_id),
       reprise_source.nouvel_id('work_spaces', s.parent_workspace_id),
       reprise_source.nouvel_id('workflow_ged', s.workflow_ged_id), coalesce(s.deleted, false),
       reprise_source.utc(s.created_at), reprise_source.utc(s.updated_at)
  FROM reprise_source.work_spaces s
 WHERE s.parent_workspace_id IS NULL;

DO $$
DECLARE
    n bigint;
BEGIN
    LOOP
        INSERT INTO noeud (id, name, code, description, status, employe_id, parent_id, workflow_ged_id,
                           supprime, created_at, updated_at)
        SELECT reprise_source.nouvel_id('work_spaces', s.id), s.name, s.code, s.description, s.status,
               reprise_source.nouvel_id('employes', s.employe_id),
               reprise_source.nouvel_id('work_spaces', s.parent_workspace_id),
               reprise_source.nouvel_id('workflow_ged', s.workflow_ged_id), coalesce(s.deleted, false),
               reprise_source.utc(s.created_at), reprise_source.utc(s.updated_at)
          FROM reprise_source.work_spaces s
         WHERE s.parent_workspace_id IS NOT NULL
           AND NOT EXISTS (SELECT 1 FROM noeud x WHERE x.id = reprise_source.nouvel_id('work_spaces', s.id))
           AND EXISTS (SELECT 1 FROM noeud p
                        WHERE p.id = reprise_source.nouvel_id('work_spaces', s.parent_workspace_id));
        GET DIAGNOSTICS n = ROW_COUNT;
        EXIT WHEN n = 0;
    END LOOP;
END
$$;

INSERT INTO groupe_ged (id, code, name, droit_access, droit_lecture, droit_modifier, droit_uploader,
                          droit_supprimer, droit_deplacer, droit_ajouter_version,
                          droit_verrouiller_deverrouiller, supprime, created_at, updated_at)
SELECT reprise_source.nouvel_id('access_groups', s.id), s.code, s.name,
       coalesce(s.droit_access, false), coalesce(s.droit_lecture, false), coalesce(s.droit_modifier, false),
       coalesce(s.droit_uploader, false), coalesce(s.droit_supprimer, false),
       coalesce(s.droit_deplacer, false), coalesce(s.droit_ajouter_version, false),
       coalesce(s.droit_verrouiller_deverrouiller, false), coalesce(s.deleted, false),
       reprise_source.utc(s.created_at), reprise_source.utc(s.updated_at)
  FROM reprise_source.access_groups s;

-- Un groupe « couvrait » des espaces : c'est désormais une habilitation du
-- groupe sur chaque nœud, avec le rôle Utilisateur standard (même sens que la
-- reprise des rattachements existants, changeset 202609281030-2). Les
-- colonnes droit_* de l'ancien modèle restent inertes.
INSERT INTO habilitation (id, sujet_type, groupe_ged_id, role_id, noeud_id, rupture_heritage)
SELECT uuid_v7(), 'GROUPE', x.groupe_ged_id, '0192a000-0000-7000-8000-000000000004'::uuid, x.noeud_id, false
  FROM (SELECT DISTINCT reprise_source.nouvel_id('access_groups', s.access_group_id) AS groupe_ged_id,
                        reprise_source.nouvel_id('work_spaces', s.workspace_id) AS noeud_id
          FROM reprise_source.pivot_workspace_groups s) x;

INSERT INTO groupe_membre (groupe_ged_id, employe_id)
SELECT reprise_source.nouvel_id('access_groups', s.access_group_id),
       reprise_source.nouvel_id('employes', s.employe_id)
  FROM reprise_source.pivot_employe_groups s;

INSERT INTO etiquette (id, code, tag, couleur, supprime, created_at, updated_at)
SELECT reprise_source.nouvel_id('etiquettes', s.id), s.code, s.tag, s.couleur, coalesce(s.deleted, false),
       reprise_source.utc(s.created_at), reprise_source.utc(s.updated_at)
  FROM reprise_source.etiquettes s;

INSERT INTO index_def (id, code, nom_index, type_champs, valeurs, valeur_par_defaut, obligatoire,
                       indexe_pour_recherche, index_de_groupage, supprime, created_at, updated_at)
SELECT reprise_source.nouvel_id('indices', s.id), s.code, s.nom_index, s.type_champs, s.valeurs,
       s.valeur_par_defaut, coalesce(s.obligatoire, false), coalesce(s.indexe_pour_recherche, false),
       coalesce(s.index_de_groupage, false), coalesce(s.deleted, false),
       reprise_source.utc(s.created_at), reprise_source.utc(s.updated_at)
  FROM reprise_source.indices s;

-- Charte de nommage : {"indexs":["3","DATE",...], ...}. Un jeton entièrement
-- numérique désignait un index par son ancien identifiant ; il devient l'UUID
-- correspondant. Les jetons système (DATE, YEAR…) et un jeton numérique sans
-- index correspondant sont conservés tels quels. Une charte illisible n'est
-- pas touchée : l'application la traite déjà comme vide.
INSERT INTO plan_indexation (id, code, nom_du_plan, mode_indexation, manuel, majuscule, separateur,
                             charte_nommage, supprime, created_at, updated_at)
SELECT reprise_source.nouvel_id('plan_d_indexations', s.id), s.code, s.nom_du_plan,
       coalesce(s.mode_indexation, false), coalesce(s.manuel, false), coalesce(s.majuscule, false),
       coalesce(s.separateur, '_'),
       CASE
           WHEN s.charte_nommage IS JSON OBJECT
                AND jsonb_typeof(s.charte_nommage::jsonb -> 'indexs') = 'array'
           THEN jsonb_set(s.charte_nommage::jsonb, '{indexs}', coalesce((
                    SELECT jsonb_agg(coalesce(to_jsonb(m.id::text), to_jsonb(j.jeton)) ORDER BY j.rang)
                      FROM jsonb_array_elements_text(s.charte_nommage::jsonb -> 'indexs')
                           WITH ORDINALITY AS j(jeton, rang)
                      LEFT JOIN reprise_source.correspondance m
                             ON m.table_source = 'indices'
                            AND j.jeton ~ '^[0-9]{1,18}$'
                            AND m.ancien_id = CASE WHEN j.jeton ~ '^[0-9]{1,18}$' THEN j.jeton::bigint END),
                    '[]'::jsonb))::text
           ELSE s.charte_nommage
       END,
       coalesce(s.deleted, false), reprise_source.utc(s.created_at), reprise_source.utc(s.updated_at)
  FROM reprise_source.plan_d_indexations s;

INSERT INTO plan_index (plan_indexation_id, index_def_id, position)
SELECT reprise_source.nouvel_id('plan_d_indexations', s.plan_d_indexation_id),
       reprise_source.nouvel_id('indices', s.index_id), s.position
  FROM reprise_source.pivot_plan_d_indexation_indices s;

INSERT INTO type_document (id, code, type_de_document, description, noeud_id, plan_indexation_id,
                           type_autorise, taille_max_mo, supprime, created_at, updated_at)
SELECT reprise_source.nouvel_id('type_de_documents', s.id), s.code, s.type_de_document, s.description,
       reprise_source.nouvel_id('work_spaces', s.workspace_id),
       reprise_source.nouvel_id('plan_d_indexations', s.plan_d_indexation_id),
       s.type_autorise, coalesce(s.taille_max_mo, 0), coalesce(s.deleted, false),
       reprise_source.utc(s.created_at), reprise_source.utc(s.updated_at)
  FROM reprise_source.type_de_documents s;

INSERT INTO document (id, name, noeud_principal_id, type_document_id, file_name, file_path, extension, size_ko,
                      expiration_date, reference, active, is_locked, verrou_le, date_document,
                      created_by_employe_id, supprime, created_at, updated_at)
SELECT reprise_source.nouvel_id('documents_file', s.id), s.name,
       reprise_source.nouvel_id('work_spaces', s.workspace_id),
       reprise_source.nouvel_id('type_de_documents', s.type_document_id),
       s.file_name, s.file_path, s.extension, coalesce(s.size_ko, 0), s.expiration_date, s.reference,
       coalesce(s.active, true), coalesce(s.is_locked, false),
       -- Verrou repris sans auteur ni motif (l'ancien modèle ne les connaissait
       -- pas) : daté de la dernière modification. Date du document = date de
       -- dépôt, l'ancien modèle n'en avait pas.
       CASE WHEN coalesce(s.is_locked, false)
            THEN coalesce(reprise_source.utc(s.updated_at), reprise_source.utc(s.created_at), now()) END,
       coalesce(reprise_source.utc(s.created_at), now())::date,
       reprise_source.nouvel_id('employes', s.created_by_employe_id), coalesce(s.deleted, false),
       reprise_source.utc(s.created_at), reprise_source.utc(s.updated_at)
  FROM reprise_source.documents_file s;

-- Versions (§12.8, lot E7) : numéros dans l'ordre de dépôt, UNE version
-- courante par document (index unique partiel) — celle que l'ancienne base
-- marquait par défaut, la plus récente si plusieurs l'étaient (course de
-- l'ancienne application), la plus récente si aucune.
INSERT INTO version_document (id, document_id, file_name, file_path, extension, size_ko, observation,
                              courante, numero, created_at, updated_at)
SELECT reprise_source.nouvel_id('document_versions', x.id),
       reprise_source.nouvel_id('documents_file', x.document_id),
       x.file_name, x.file_path, x.extension, coalesce(x.size_ko, 0), x.observation,
       x.rang_courante = 1, x.numero, reprise_source.utc(x.created_at), reprise_source.utc(x.updated_at)
  FROM (SELECT s.*,
               row_number() OVER (PARTITION BY s.document_id ORDER BY s.created_at, s.id) AS numero,
               row_number() OVER (PARTITION BY s.document_id
                                  ORDER BY coalesce(s.is_default, false) DESC, s.created_at DESC, s.id DESC) AS rang_courante
          FROM reprise_source.document_versions s) x;

INSERT INTO document_etiquette (document_id, etiquette_id)
SELECT reprise_source.nouvel_id('documents_file', s.document_id),
       reprise_source.nouvel_id('etiquettes', s.etiquette_id)
  FROM reprise_source.pivot_document_etiquettes s;

INSERT INTO document_index_valeur (id, document_id, index_def_id, valeur, created_at, updated_at)
SELECT reprise_source.nouvel_id('document_index_values', s.id),
       reprise_source.nouvel_id('documents_file', s.document_id),
       reprise_source.nouvel_id('indices', s.index_field_id), s.valeur,
       reprise_source.utc(s.created_at), reprise_source.utc(s.updated_at)
  FROM reprise_source.document_index_values s;

INSERT INTO workflow_ged_signature (id, document_id, employe_id, step_label, step_order, status, signed_at,
                                    motif, created_at, updated_at)
SELECT reprise_source.nouvel_id('workflow_ged_signatures', s.id),
       reprise_source.nouvel_id('documents_file', s.document_id),
       reprise_source.nouvel_id('employes', s.employe_id), s.step_label, s.step_order, s.status,
       reprise_source.utc(s.signed_at), s.motif,
       reprise_source.utc(s.created_at), reprise_source.utc(s.updated_at)
  FROM reprise_source.workflow_ged_signatures s;
