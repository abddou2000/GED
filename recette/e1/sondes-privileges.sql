-- Sondes dynamiques des privilèges (étape E1, §4.2.3 ; tables d'audit §7.4.2).
--
-- Le catalogue (controles-socle.sql) dit ce qui est ACCORDÉ ; ces sondes vérifient ce
-- qui est EFFECTIVEMENT POSSIBLE, héritages de rôles compris, en tentant réellement
-- chaque opération sous le rôle concerné (SET ROLE). Chaque tentative s'exécute dans
-- une sous-transaction systématiquement annulée : aucune donnée ni aucun objet n'est
-- modifié, y compris quand l'opération est autorisée.
--
-- À exécuter par un rôle membre des trois rôles testés (en pratique le superutilisateur
-- de l'environnement de recette). Sortie : sonde|role|operation|attendu|obtenu.

\set ON_ERROR_STOP 1
SET client_min_messages = warning;

CREATE FUNCTION pg_temp.qa_sonde(p_role text, p_sql text) RETURNS text
LANGUAGE plpgsql AS $$
BEGIN
  BEGIN
    EXECUTE format('SET LOCAL ROLE %I', p_role);
    EXECUTE p_sql;
    -- L'opération a réussi : on l'annule en levant une erreur privée.
    RAISE EXCEPTION USING ERRCODE = 'QA001', MESSAGE = 'annulation de la sonde';
  EXCEPTION
    WHEN insufficient_privilege THEN RETURN 'REFUSE';
    WHEN SQLSTATE 'QA001' THEN RETURN 'AUTORISE';
    -- Classes 22 et 23 (donnée, contrainte) : le contrôle de privilège est déjà
    -- passé, l'échec vient de la ligne vide insérée, pas d'un refus de droit.
    WHEN data_exception OR integrity_constraint_violation THEN RETURN 'AUTORISE';
    WHEN OTHERS THEN RETURN 'INDETERMINE:' || SQLSTATE;
  END;
END $$;

-- Table cible des sondes : la table des documents si elle existe, sinon la première
-- table applicative (hors Liquibase et hors audit).
SELECT coalesce(
  (SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = :'schema' AND c.relname = :'t_document' AND c.relkind IN ('r', 'p')),
  (SELECT min(c.relname) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = :'schema' AND c.relkind IN ('r', 'p') AND NOT c.relispartition
     AND c.relname NOT LIKE 'databasechangelog%' AND c.relname !~ :'regex_audit'),
  '') AS qa_cible \gset

SELECT coalesce(
  (SELECT min(c.relname) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = :'schema' AND c.relkind IN ('r', 'p') AND NOT c.relispartition AND c.relname ~ :'regex_audit'),
  '') AS qa_audit \gset

SELECT :'qa_cible' <> '' AS qa_a_cible, :'qa_audit' <> '' AS qa_a_audit \gset

-- Colonne modifiable pour la sonde UPDATE : une colonne IDENTITY ALWAYS ou générée
-- refuserait « SET x = x » pour une raison étrangère aux privilèges (SQLSTATE 428C9).
SELECT coalesce((SELECT a.attname FROM pg_attribute a
                 WHERE a.attrelid = to_regclass(quote_ident(:'schema') || '.' || quote_ident(nullif(:'qa_cible', '')))
                   AND a.attnum > 0 AND NOT a.attisdropped AND a.attidentity <> 'a' AND a.attgenerated = ''
                 ORDER BY a.attnum LIMIT 1), 'id') AS qa_col_cible,
       coalesce((SELECT a.attname FROM pg_attribute a
                 WHERE a.attrelid = to_regclass(quote_ident(:'schema') || '.' || quote_ident(nullif(:'qa_audit', '')))
                   AND a.attnum > 0 AND NOT a.attisdropped AND a.attidentity <> 'a' AND a.attgenerated = ''
                 ORDER BY a.attnum LIMIT 1), 'id') AS qa_col_audit \gset

-- DDL : toujours testable, même sur un schéma vide.
SELECT 'E1-P01', :'r_app', 'CREATE TABLE', 'REFUSE', pg_temp.qa_sonde(:'r_app', format('CREATE TABLE %I.qa_sonde_ddl (x int)', :'schema'))
UNION ALL
SELECT 'E1-P12', :'r_ro', 'CREATE TABLE', 'REFUSE', pg_temp.qa_sonde(:'r_ro', format('CREATE TABLE %I.qa_sonde_ddl (x int)', :'schema'));

\if :qa_a_cible
SELECT 'E1-P02', :'r_app', 'ALTER TABLE ' || :'qa_cible', 'REFUSE', pg_temp.qa_sonde(:'r_app', format('ALTER TABLE %I.%I ADD COLUMN qa_sonde int', :'schema', :'qa_cible'))
UNION ALL
SELECT 'E1-P03', :'r_app', 'DROP TABLE ' || :'qa_cible', 'REFUSE', pg_temp.qa_sonde(:'r_app', format('DROP TABLE %I.%I CASCADE', :'schema', :'qa_cible'))
UNION ALL
SELECT 'E1-P04', :'r_app', 'SELECT ' || :'qa_cible', 'AUTORISE', pg_temp.qa_sonde(:'r_app', format('SELECT 1 FROM %I.%I LIMIT 1', :'schema', :'qa_cible'))
UNION ALL
SELECT 'E1-P05', :'r_app', 'INSERT ' || :'qa_cible', 'AUTORISE', pg_temp.qa_sonde(:'r_app', format('INSERT INTO %I.%I DEFAULT VALUES', :'schema', :'qa_cible'))
UNION ALL
SELECT 'E1-P06', :'r_app', 'UPDATE ' || :'qa_cible', 'AUTORISE', pg_temp.qa_sonde(:'r_app', format('UPDATE %I.%I SET %I = %I WHERE false', :'schema', :'qa_cible', :'qa_col_cible', :'qa_col_cible'))
UNION ALL
SELECT 'E1-P07', :'r_app', 'DELETE ' || :'qa_cible', 'AUTORISE', pg_temp.qa_sonde(:'r_app', format('DELETE FROM %I.%I WHERE false', :'schema', :'qa_cible'))
UNION ALL
SELECT 'E1-P08', :'r_ro', 'SELECT ' || :'qa_cible', 'AUTORISE', pg_temp.qa_sonde(:'r_ro', format('SELECT 1 FROM %I.%I LIMIT 1', :'schema', :'qa_cible'))
UNION ALL
SELECT 'E1-P09', :'r_ro', 'INSERT ' || :'qa_cible', 'REFUSE', pg_temp.qa_sonde(:'r_ro', format('INSERT INTO %I.%I DEFAULT VALUES', :'schema', :'qa_cible'))
UNION ALL
SELECT 'E1-P10', :'r_ro', 'UPDATE ' || :'qa_cible', 'REFUSE', pg_temp.qa_sonde(:'r_ro', format('UPDATE %I.%I SET %I = %I WHERE false', :'schema', :'qa_cible', :'qa_col_cible', :'qa_col_cible'))
UNION ALL
SELECT 'E1-P11', :'r_ro', 'DELETE ' || :'qa_cible', 'REFUSE', pg_temp.qa_sonde(:'r_ro', format('DELETE FROM %I.%I WHERE false', :'schema', :'qa_cible'))
UNION ALL
SELECT 'E1-P13', :'r_ro', 'TRUNCATE ' || :'qa_cible', 'REFUSE', pg_temp.qa_sonde(:'r_ro', format('TRUNCATE %I.%I CASCADE', :'schema', :'qa_cible'));
\else
SELECT 'E1-P02', :'r_app', 'DML/DDL sur table applicative', 'NA', 'aucune table applicative dans le schéma';
\endif

\if :qa_a_audit
SELECT 'E1-P20', :'r_app', 'INSERT ' || :'qa_audit', 'AUTORISE', pg_temp.qa_sonde(:'r_app', format('INSERT INTO %I.%I DEFAULT VALUES', :'schema', :'qa_audit'))
UNION ALL
SELECT 'E1-P21', :'r_app', 'UPDATE ' || :'qa_audit', 'REFUSE', pg_temp.qa_sonde(:'r_app', format('UPDATE %I.%I SET %I = %I WHERE false', :'schema', :'qa_audit', :'qa_col_audit', :'qa_col_audit'))
UNION ALL
SELECT 'E1-P22', :'r_app', 'DELETE ' || :'qa_audit', 'REFUSE', pg_temp.qa_sonde(:'r_app', format('DELETE FROM %I.%I WHERE false', :'schema', :'qa_audit'))
UNION ALL
SELECT 'E1-P23', :'r_app', 'TRUNCATE ' || :'qa_audit', 'REFUSE', pg_temp.qa_sonde(:'r_app', format('TRUNCATE %I.%I', :'schema', :'qa_audit'));
\else
SELECT 'E1-P20', :'r_app', 'tables d''audit', 'NA', 'aucune table d''audit (livrée en E4)';
\endif
