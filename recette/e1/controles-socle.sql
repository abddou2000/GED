-- Contrôles statiques du socle de données (étape E1) — lecture seule du catalogue.
--
-- Chaque requête n'écrit que des CONSTATS (violations, avertissements, non-applicables)
-- dans une table temporaire ; l'absence de constat pour un contrôle vaut « OK ».
-- Sortie finale : controle|gravite|objet|detail (psql -At -F'|').
--
-- Variables psql attendues (fournies par verifier-socle.sh) :
--   schema, r_owner, r_app, r_ro   schéma applicatif et noms des trois rôles
--   schema_lb                      schéma du registre Liquibase (ged_liquibase chez dev1)
--   t_document                     nom de la table des documents (défaut : document)
--   regex_audit                    tables d'audit (défaut : ^journal_audit)
--   base_vierge                    1 = la base vient d'être créée par Liquibase seul
--   regex_referentiels             tables de référentiels métier (vides sur base vierge)
--
-- Références : DAT V3 §2.2, §4.2.1, §4.2.2, §4.2.3, §5.3.2, §12.1, §12.5, §12.7.

\set ON_ERROR_STOP 1
SET client_min_messages = warning;
-- Les tables Liquibase sont lues sans qualification : schéma du registre d'abord.
SELECT set_config('search_path', quote_ident(:'schema_lb') || ', ' || quote_ident(:'schema') || ', public', false) AS qa_sp \gset

CREATE TEMP TABLE qa_constats (controle text, gravite text, objet text, detail text);

-- Relations applicatives : tables, tables partitionnées (parent seulement), vues.
-- Les tables techniques de Liquibase ne suivent pas nos conventions et sont exclues.
CREATE TEMP TABLE qa_rel AS
SELECT c.oid, c.relname, c.relkind
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = :'schema'
  AND c.relkind IN ('r', 'p', 'v', 'm')
  AND NOT c.relispartition
  AND c.relname NOT IN ('databasechangelog', 'databasechangeloglock');

CREATE TEMP TABLE qa_tab AS SELECT * FROM qa_rel WHERE relkind IN ('r', 'p');

-- ------------------------------------------------------------- base et serveur
-- E1-C24 : encodage UTF-8 (§5.3.2 JSON UTF-8)
INSERT INTO qa_constats
SELECT 'E1-C24', 'ERREUR', datname, 'encodage ' || pg_encoding_to_char(encoding) || ' (UTF8 attendu)'
FROM pg_database WHERE datname = current_database() AND pg_encoding_to_char(encoding) <> 'UTF8';

-- E1-C26 : PostgreSQL 16 ou plus (§2.2)
INSERT INTO qa_constats
SELECT 'E1-C26', 'ERREUR', 'serveur', 'version ' || current_setting('server_version') || ' (16 ou plus attendu)'
WHERE current_setting('server_version_num')::int < 160000;

-- E1-C25 : configurations de recherche french et arabic, extensions (§2.2, §4.4)
INSERT INTO qa_constats
SELECT 'E1-C25', 'ERREUR', 'pg_ts_config.' || cfg, 'configuration de recherche absente'
FROM (VALUES ('french'), ('arabic')) v(cfg)
WHERE NOT EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = v.cfg);
INSERT INTO qa_constats
-- Extensions : exigées par la recherche plein texte (E6, §4.4) ; avertissement seulement en E1,
-- où seule la configuration arabic est exigée (matrice 2.2).
SELECT 'E1-C25', 'AVERT', 'extension ' || ext,
       'extension non installée dans la base ' || current_database() || ' (requise en E6, §4.4)'
FROM (VALUES ('unaccent'), ('pg_trgm')) v(ext)
WHERE NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = v.ext);

-- ------------------------------------------------------------- nommage (§4.2.2)
-- E1-C01 : tables et vues en snake_case minuscule
INSERT INTO qa_constats
SELECT 'E1-C01', 'ERREUR', relname, 'nom de relation non snake_case'
FROM qa_rel WHERE relname !~ '^[a-z][a-z0-9]*(_[a-z0-9]+)*$';

-- E1-C02 : colonnes en snake_case minuscule
INSERT INTO qa_constats
SELECT 'E1-C02', 'ERREUR', r.relname || '.' || a.attname, 'nom de colonne non snake_case'
FROM qa_rel r JOIN pg_attribute a ON a.attrelid = r.oid AND a.attnum > 0 AND NOT a.attisdropped
WHERE a.attname !~ '^[a-z][a-z0-9]*(_[a-z0-9]+)*$';

-- E1-C07 : colonnes de clé étrangère nommées <table>_id (au minimum suffixe _id ;
-- le DAT lui-même qualifie certains rôles : parent_id, noeud_principal_id, auteur_id)
INSERT INTO qa_constats
SELECT 'E1-C07', 'ERREUR', t.relname || '.' || a.attname, 'colonne de clé étrangère sans suffixe _id (' || k.conname || ')'
FROM pg_constraint k JOIN qa_tab t ON t.oid = k.conrelid
JOIN LATERAL unnest(k.conkey) AS u(attnum) ON true
JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = u.attnum
WHERE k.contype = 'f' AND a.attname !~ '_id$';

-- E1-C08 / C09 / C10 : préfixes des contraintes fk_, uk_, ck_
INSERT INTO qa_constats
SELECT CASE k.contype WHEN 'f' THEN 'E1-C08' WHEN 'u' THEN 'E1-C09' ELSE 'E1-C10' END,
       'ERREUR', t.relname || '.' || k.conname,
       'contrainte ' || CASE k.contype WHEN 'f' THEN 'de clé étrangère sans préfixe fk_'
                                        WHEN 'u' THEN 'd''unicité sans préfixe uk_'
                                        ELSE 'de vérification sans préfixe ck_' END
FROM pg_constraint k JOIN qa_tab t ON t.oid = k.conrelid
WHERE (k.contype = 'f' AND k.conname !~ '^fk_')
   OR (k.contype = 'u' AND k.conname !~ '^uk_')
   OR (k.contype = 'c' AND k.conname !~ '^ck_');

-- E1-C11 : index hors contrainte préfixés idx_ (un index unique autonome peut
-- porter uk_, par exemple l'index unique partiel de la version courante, §12.8)
INSERT INTO qa_constats
SELECT 'E1-C11', 'ERREUR', t.relname || '.' || ic.relname, 'index sans préfixe idx_' || CASE WHEN i.indisunique THEN ' ni uk_' ELSE '' END
FROM pg_index i JOIN qa_tab t ON t.oid = i.indrelid JOIN pg_class ic ON ic.oid = i.indexrelid
WHERE NOT EXISTS (SELECT 1 FROM pg_constraint k WHERE k.conindid = i.indexrelid AND k.conrelid = i.indrelid)
  AND ic.relname !~ '^idx_'
  AND NOT (i.indisunique AND ic.relname ~ '^uk_');

-- E1-C12 : forme idx_<table>_<colonnes> (avertissement : un nom tronqué à 63 caractères reste acceptable)
INSERT INTO qa_constats
SELECT 'E1-C12', 'AVERT', t.relname || '.' || ic.relname, 'index idx_ ne commençant pas par idx_' || t.relname || '_'
FROM pg_index i JOIN qa_tab t ON t.oid = i.indrelid JOIN pg_class ic ON ic.oid = i.indexrelid
WHERE ic.relname ~ '^idx_' AND left(ic.relname, 63) NOT LIKE left('idx_' || t.relname || '_', 63) || '%';

-- E1-C13 : clé étrangère sans index de tête (jointures et suppressions en cascade lentes)
INSERT INTO qa_constats
SELECT 'E1-C13', 'AVERT', t.relname || '.' || k.conname, 'aucun index ne commence par la colonne de la clé étrangère'
FROM pg_constraint k JOIN qa_tab t ON t.oid = k.conrelid
WHERE k.contype = 'f'
  AND NOT EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = k.conrelid AND i.indkey[0] = k.conkey[1]);

-- ------------------------------------------------------------- clés (§12.1, §5.3.2)
-- E1-C03 : toute table porte une clé primaire
INSERT INTO qa_constats
SELECT 'E1-C03', 'ERREUR', relname, 'table sans clé primaire'
FROM qa_tab t WHERE NOT EXISTS (SELECT 1 FROM pg_constraint k WHERE k.conrelid = t.oid AND k.contype = 'p');

-- E1-C04 : clé primaire = la seule colonne id
INSERT INTO qa_constats
SELECT 'E1-C04', 'ERREUR', t.relname, 'clé primaire (' || string_agg(a.attname, ', ' ORDER BY a.attnum) || ') au lieu de (id)'
FROM pg_constraint k JOIN qa_tab t ON t.oid = k.conrelid
JOIN LATERAL unnest(k.conkey) AS u(attnum) ON true
JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = u.attnum
WHERE k.contype = 'p'
GROUP BY t.relname
HAVING NOT (count(*) = 1 AND bool_and(a.attname = 'id'));

-- E1-C05 : colonnes id de type uuid
INSERT INTO qa_constats
SELECT 'E1-C05', 'ERREUR', t.relname || '.id', 'type ' || format_type(a.atttypid, a.atttypmod) || ' (uuid attendu)'
FROM qa_tab t JOIN pg_attribute a ON a.attrelid = t.oid AND a.attname = 'id' AND NOT a.attisdropped
WHERE a.atttypid <> 'uuid'::regtype;

-- E1-C06 : aucune colonne auto-incrémentée (identity, serial, nextval)
INSERT INTO qa_constats
SELECT 'E1-C06', 'ERREUR', t.relname || '.' || a.attname,
       CASE WHEN a.attidentity <> '' THEN 'colonne IDENTITY' ELSE 'valeur par défaut ' || pg_get_expr(d.adbin, d.adrelid) END
FROM qa_tab t JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum > 0 AND NOT a.attisdropped
LEFT JOIN pg_attrdef d ON d.adrelid = t.oid AND d.adnum = a.attnum
WHERE a.attidentity <> '' OR pg_get_expr(d.adbin, d.adrelid) LIKE 'nextval(%';

-- E1-C14 : horodatages sans fuseau (les dates d'API sont en UTC, §5.3.2)
INSERT INTO qa_constats
SELECT 'E1-C14', 'AVERT', t.relname || '.' || a.attname, 'timestamp without time zone (timestamptz recommandé)'
FROM qa_tab t JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum > 0 AND NOT a.attisdropped
WHERE a.atttypid = 'timestamp'::regtype;

-- ------------------------------------------------------------- suppression douce (§12.5)
-- E1-C15 : le triplet supprime / supprime_par / supprime_le est complet et typé
INSERT INTO qa_constats
SELECT 'E1-C15', 'ERREUR', t.relname, 'suppression douce incomplète : manque ' || string_agg(m.col, ', ')
FROM qa_tab t
CROSS JOIN (VALUES ('supprime'), ('supprime_par'), ('supprime_le')) m(col)
WHERE EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = t.oid AND a.attname IN ('supprime', 'supprime_par', 'supprime_le') AND NOT a.attisdropped)
  AND NOT EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = t.oid AND a.attname = m.col AND NOT a.attisdropped)
GROUP BY t.relname;
INSERT INTO qa_constats
SELECT 'E1-C15', 'ERREUR', t.relname || '.' || a.attname,
       'type ' || format_type(a.atttypid, a.atttypmod) || ' (' || CASE a.attname WHEN 'supprime_par' THEN 'uuid' ELSE 'timestamptz' END || ' attendu)'
FROM qa_tab t JOIN pg_attribute a ON a.attrelid = t.oid AND NOT a.attisdropped
WHERE (a.attname = 'supprime_par' AND a.atttypid <> 'uuid'::regtype)
   OR (a.attname = 'supprime_le' AND a.atttypid <> 'timestamptz'::regtype);

-- E1-C16 : la table des documents existe et porte la suppression douce
INSERT INTO qa_constats
SELECT 'E1-C16', 'ERREUR', :'t_document', 'table des documents absente du schéma ' || :'schema'
WHERE NOT EXISTS (SELECT 1 FROM qa_tab WHERE relname = :'t_document');
INSERT INTO qa_constats
SELECT 'E1-C16', 'ERREUR', :'t_document' || '.' || m.col, 'colonne de suppression douce absente'
FROM (VALUES ('supprime'), ('supprime_par'), ('supprime_le')) m(col)
WHERE EXISTS (SELECT 1 FROM qa_tab WHERE relname = :'t_document')
  AND NOT EXISTS (SELECT 1 FROM qa_tab t JOIN pg_attribute a ON a.attrelid = t.oid
                  WHERE t.relname = :'t_document' AND a.attname = m.col AND NOT a.attisdropped);

-- ------------------------------------------------------------- métadonnées JSONB (§12.7)
-- E1-C17 : document.metadonnees de type jsonb
INSERT INTO qa_constats
SELECT 'E1-C17', 'ERREUR', :'t_document' || '.metadonnees',
       coalesce('type ' || (SELECT format_type(a.atttypid, a.atttypmod) FROM qa_tab t JOIN pg_attribute a ON a.attrelid = t.oid
                            WHERE t.relname = :'t_document' AND a.attname = 'metadonnees' AND NOT a.attisdropped) || ' (jsonb attendu)',
                'colonne absente')
WHERE NOT EXISTS (SELECT 1 FROM qa_tab t JOIN pg_attribute a ON a.attrelid = t.oid
                  WHERE t.relname = :'t_document' AND a.attname = 'metadonnees' AND a.atttypid = 'jsonb'::regtype AND NOT a.attisdropped);

-- E1-C18 : index GIN sur document.metadonnees
INSERT INTO qa_constats
SELECT 'E1-C18', 'ERREUR', :'t_document' || '.metadonnees', 'aucun index GIN sur la colonne'
WHERE NOT EXISTS (
  SELECT 1 FROM pg_index i JOIN qa_tab t ON t.oid = i.indrelid
  JOIN pg_class ic ON ic.oid = i.indexrelid JOIN pg_am am ON am.oid = ic.relam
  JOIN pg_attribute a ON a.attrelid = t.oid AND a.attname = 'metadonnees'
  WHERE t.relname = :'t_document' AND am.amname = 'gin'
    AND (a.attnum = ANY (i.indkey::int2[]) OR pg_get_indexdef(i.indexrelid) ~ 'metadonnees'));

-- ------------------------------------------------------------- Liquibase (§2.2, §4.2.1, §4.2.2)
INSERT INTO qa_constats
SELECT 'E1-C19', 'ERREUR', :'schema' || '.flyway_schema_history', 'table Flyway présente : l''outil imposé est Liquibase'
WHERE to_regclass(quote_ident(:'schema') || '.flyway_schema_history') IS NOT NULL;

SELECT to_regclass(quote_ident(:'schema_lb') || '.databasechangelog') IS NOT NULL AS qa_dcl \gset
\if :qa_dcl
  INSERT INTO qa_constats
  SELECT 'E1-C19', 'ERREUR', 'databasechangelog', 'journal Liquibase vide' WHERE NOT EXISTS (SELECT 1 FROM databasechangelog);

  -- E1-C20 : un fichier par évolution, nommé AAAAMMJJHHmm_objet_metier.xml
  INSERT INTO qa_constats
  SELECT 'E1-C20', 'ERREUR', filename || '::' || id, 'fichier de changeset hors convention AAAAMMJJHHmm_objet.xml'
  FROM databasechangelog
  WHERE regexp_replace(filename, '^.*[/\\]', '') !~ '^[0-9]{12}_[a-z0-9]+(_[a-z0-9]+)*\.xml$';

  -- E1-C21 : exécution des changesets
  INSERT INTO qa_constats
  SELECT 'E1-C21', CASE WHEN exectype = 'FAILED' THEN 'ERREUR' ELSE 'AVERT' END, filename || '::' || id, 'exectype ' || exectype
  FROM databasechangelog WHERE exectype NOT IN ('EXECUTED', 'MARK_RAN');

  -- E1-C22 : amorçage initial porté par des changesets data-initial (§4.2.1)
  INSERT INTO qa_constats
  SELECT 'E1-C22', 'ERREUR', 'databasechangelog', 'aucun changeset étiqueté data-initial (contexte ou label)'
  WHERE NOT EXISTS (SELECT 1 FROM databasechangelog WHERE coalesce(contexts, '') || ' ' || coalesce(labels, '') ~* 'data-initial');
\else
  INSERT INTO qa_constats VALUES ('E1-C19', 'ERREUR', :'schema_lb' || '.databasechangelog', 'journal Liquibase absent : le schéma n''a pas été créé par Liquibase');
\endif

-- E1-C23 : sur base vierge, les référentiels métier sont vides (principe P1 : ils se
-- créent depuis l'interface, jamais par changeset). Comptage dynamique, d'où le DO.
\if :base_vierge
SELECT set_config('qa.schema', :'schema', false) AS qa_g1, set_config('qa.regex_ref', :'regex_referentiels', false) AS qa_g2 \gset
DO $$
DECLARE r record; n bigint;
BEGIN
  FOR r IN SELECT c.relname FROM pg_class c JOIN pg_namespace s ON s.oid = c.relnamespace
           WHERE s.nspname = current_setting('qa.schema') AND c.relkind IN ('r', 'p') AND NOT c.relispartition
             AND c.relname ~ current_setting('qa.regex_ref')
  LOOP
    EXECUTE format('SELECT count(*) FROM %I.%I', current_setting('qa.schema'), r.relname) INTO n;
    IF n > 0 THEN
      INSERT INTO qa_constats VALUES ('E1-C23', 'ERREUR', r.relname, n || ' ligne(s) sur base vierge : référentiel métier amorcé par changeset');
    END IF;
  END LOOP;
END $$;
\endif

-- ------------------------------------------------------------- rôles (§4.2.3)
INSERT INTO qa_constats
SELECT 'E1-C30', 'ERREUR', r, 'rôle absent'
FROM (VALUES (:'r_owner'), (:'r_app'), (:'r_ro')) v(r)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v.r);

SELECT count(*) = 3 AS qa_roles FROM pg_roles WHERE rolname IN (:'r_owner', :'r_app', :'r_ro') \gset
\if :qa_roles

-- E1-C31 : aucun des trois rôles n'est superutilisateur ni ne détient d'attribut d'administration
INSERT INTO qa_constats
SELECT 'E1-C31', 'ERREUR', rolname,
       concat_ws(', ', CASE WHEN rolsuper THEN 'SUPERUSER' END, CASE WHEN rolcreaterole THEN 'CREATEROLE' END,
                 CASE WHEN rolcreatedb THEN 'CREATEDB' END, CASE WHEN rolbypassrls THEN 'BYPASSRLS' END,
                 CASE WHEN rolreplication THEN 'REPLICATION' END)
FROM pg_roles WHERE rolname IN (:'r_owner', :'r_app', :'r_ro')
  AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls OR rolreplication);

-- E1-C32 : ged_owner possède le schéma et tous ses objets
INSERT INTO qa_constats
SELECT 'E1-C32', 'ERREUR', 'schéma ' || nspname, 'propriétaire ' || pg_get_userbyid(nspowner) || ' (' || :'r_owner' || ' attendu)'
FROM pg_namespace WHERE nspname IN (:'schema', :'schema_lb') AND pg_get_userbyid(nspowner) <> :'r_owner';
INSERT INTO qa_constats
SELECT 'E1-C32', 'ERREUR', c.relname, 'propriétaire ' || pg_get_userbyid(c.relowner) || ' (' || :'r_owner' || ' attendu)'
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname IN (:'schema', :'schema_lb') AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f') AND pg_get_userbyid(c.relowner) <> :'r_owner';
INSERT INTO qa_constats
SELECT 'E1-C32', 'ERREUR', p.proname || '()', 'propriétaire ' || pg_get_userbyid(p.proowner) || ' (' || :'r_owner' || ' attendu)'
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = :'schema' AND pg_get_userbyid(p.proowner) <> :'r_owner'
  AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.objid = p.oid AND d.deptype = 'e');

-- E1-C33 : ged_app n'a aucun droit DDL (ni CREATE sur schéma ou base, ni appartenance à ged_owner)
INSERT INTO qa_constats
SELECT 'E1-C33', 'ERREUR', :'r_app', x FROM (
  SELECT 'CREATE sur le schéma ' || :'schema' AS x WHERE has_schema_privilege(:'r_app', :'schema', 'CREATE')
  UNION ALL SELECT 'CREATE sur le schéma ' || :'schema_lb' WHERE :'schema_lb' <> :'schema' AND has_schema_privilege(:'r_app', :'schema_lb', 'CREATE')
  UNION ALL SELECT 'CREATE sur la base ' || current_database() WHERE has_database_privilege(:'r_app', current_database(), 'CREATE')
  UNION ALL SELECT 'membre de ' || :'r_owner' || ' (hérite de la propriété des objets)' WHERE pg_has_role(:'r_app', :'r_owner', 'MEMBER')
) s;

-- E1-C34 : ged_app détient SELECT, INSERT, UPDATE, DELETE sur chaque table applicative hors audit
INSERT INTO qa_constats
SELECT 'E1-C34', 'ERREUR', :'r_app', 'USAGE absent sur le schéma ' || :'schema'
WHERE NOT has_schema_privilege(:'r_app', :'schema', 'USAGE');
INSERT INTO qa_constats
SELECT 'E1-C34', 'ERREUR', t.relname, :'r_app' || ' sans ' || p.priv
FROM qa_tab t CROSS JOIN (VALUES ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE')) p(priv)
WHERE t.relname !~ :'regex_audit'
  AND NOT has_table_privilege(:'r_app', t.oid, p.priv);

-- E1-C35 : ged_app ne détient pas de droit hors DML
INSERT INTO qa_constats
SELECT 'E1-C35', CASE WHEN p.priv = 'TRUNCATE' THEN 'AVERT' ELSE 'ERREUR' END, t.relname, :'r_app' || ' détient ' || p.priv
FROM qa_tab t CROSS JOIN (VALUES ('TRUNCATE'), ('REFERENCES'), ('TRIGGER')) p(priv)
WHERE t.relname !~ :'regex_audit' AND has_table_privilege(:'r_app', t.oid, p.priv);

-- E1-C36 : tables d'audit en INSERT et SELECT seulement pour ged_app (§4.2.3, §7.4.2)
INSERT INTO qa_constats
SELECT 'E1-C36', 'ERREUR', t.relname, :'r_app' || CASE WHEN p.attendu THEN ' sans ' ELSE ' détient ' END || p.priv
FROM qa_tab t CROSS JOIN (VALUES ('SELECT', true), ('INSERT', true), ('UPDATE', false), ('DELETE', false),
                                 ('TRUNCATE', false), ('REFERENCES', false), ('TRIGGER', false)) p(priv, attendu)
WHERE t.relname ~ :'regex_audit' AND has_table_privilege(:'r_app', t.oid, p.priv) <> p.attendu;
INSERT INTO qa_constats
SELECT 'E1-C36', 'NA', 'audit', 'aucune table correspondant à ' || :'regex_audit' || ' (journal d''audit livré en E4)'
WHERE NOT EXISTS (SELECT 1 FROM qa_tab WHERE relname ~ :'regex_audit');

-- E1-C37 : l'application n'écrit pas dans le journal Liquibase
INSERT INTO qa_constats
SELECT 'E1-C37', 'AVERT', c.relname, :'r_app' || ' détient ' || p.priv
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
CROSS JOIN (VALUES ('INSERT'), ('UPDATE'), ('DELETE'), ('TRUNCATE')) p(priv)
WHERE n.nspname IN (:'schema', :'schema_lb') AND c.relname IN ('databasechangelog', 'databasechangeloglock')
  AND has_table_privilege(:'r_app', c.oid, p.priv);

-- E1-C38 : ged_readonly ne détient que SELECT
INSERT INTO qa_constats
SELECT 'E1-C38', 'ERREUR', c.relname, :'r_ro' || ' détient ' || p.priv
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
CROSS JOIN (VALUES ('INSERT'), ('UPDATE'), ('DELETE'), ('TRUNCATE'), ('REFERENCES'), ('TRIGGER')) p(priv)
WHERE n.nspname = :'schema' AND c.relkind IN ('r', 'p') AND has_table_privilege(:'r_ro', c.oid, p.priv);
INSERT INTO qa_constats
SELECT 'E1-C38', 'ERREUR', :'r_ro', x FROM (
  SELECT 'CREATE sur le schéma ' || :'schema' AS x WHERE has_schema_privilege(:'r_ro', :'schema', 'CREATE')
  UNION ALL SELECT 'CREATE sur la base ' || current_database() WHERE has_database_privilege(:'r_ro', current_database(), 'CREATE')
  UNION ALL SELECT 'membre de ' || v.r FROM (VALUES (:'r_owner'), (:'r_app')) v(r) WHERE pg_has_role(:'r_ro', v.r, 'MEMBER')
) s;

-- E1-C39 : ged_readonly lit toutes les tables (diagnostic et supervision)
INSERT INTO qa_constats
SELECT 'E1-C39', 'ERREUR', :'r_ro', 'USAGE absent sur le schéma ' || :'schema'
WHERE NOT has_schema_privilege(:'r_ro', :'schema', 'USAGE');
INSERT INTO qa_constats
SELECT 'E1-C39', 'ERREUR', t.relname, :'r_ro' || ' sans SELECT'
FROM qa_tab t WHERE NOT has_table_privilege(:'r_ro', t.oid, 'SELECT');

-- E1-C41 : privilèges par défaut, sans lesquels la prochaine table livrée échapperait aux rôles
INSERT INTO qa_constats
SELECT 'E1-C41', 'AVERT', :'r_owner', 'aucun ALTER DEFAULT PRIVILEGES accordant les tables à ' || g
FROM (VALUES (:'r_app'), (:'r_ro')) v(g)
WHERE NOT EXISTS (
  SELECT 1 FROM pg_default_acl d
  CROSS JOIN LATERAL aclexplode(d.defaclacl) x
  WHERE d.defaclrole = (SELECT oid FROM pg_roles WHERE rolname = :'r_owner')
    AND d.defaclobjtype = 'r'
    AND (d.defaclnamespace = 0 OR d.defaclnamespace = (SELECT oid FROM pg_namespace WHERE nspname = :'schema'))
    AND x.grantee = (SELECT oid FROM pg_roles WHERE rolname = v.g));

\endif

-- E1-C40 : PUBLIC n'a ni CREATE sur le schéma ni droit sur les tables
INSERT INTO qa_constats
SELECT 'E1-C40', 'ERREUR', 'schéma ' || n.nspname, 'PUBLIC détient ' || x.privilege_type
FROM pg_namespace n CROSS JOIN LATERAL aclexplode(coalesce(n.nspacl, acldefault('n', n.nspowner))) x
WHERE n.nspname = :'schema' AND x.grantee = 0 AND x.privilege_type = 'CREATE';
INSERT INTO qa_constats
SELECT 'E1-C40', 'ERREUR', t.relname, 'PUBLIC détient ' || x.privilege_type
FROM qa_tab t JOIN pg_class c ON c.oid = t.oid CROSS JOIN LATERAL aclexplode(c.relacl) x
WHERE x.grantee = 0;

SELECT controle, gravite, objet, replace(detail, '|', '/') FROM qa_constats ORDER BY controle, gravite, objet;
