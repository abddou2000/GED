-- =====================================================================
--  GED Marchica Med — préparation d'une base (dossier technique §4.2.3)
-- =====================================================================
--  Crée la base si elle manque, ses deux schémas et les droits des trois rôles
--  (créés au préalable par creer-roles.sql). Idempotent : peut être rejoué.
--
--  Deux schémas, tous deux propriété de ged_owner :
--    ged            tables applicatives ;
--    ged_liquibase  registre des migrations (DATABASECHANGELOG et son verrou).
--                   Séparé pour que ged_app ne puisse ni lire ni réécrire
--                   l'historique des migrations : les privilèges par défaut
--                   accordés à ged_app ne portent que sur le schéma `ged`.
--
--  Droits :
--    ged_owner     propriétaire des schémas et de tous les objets (DDL) ;
--    ged_app       SELECT, INSERT, UPDATE, DELETE sur les tables de `ged` ;
--    ged_readonly  SELECT sur `ged` et sur le registre `ged_liquibase`.
--  Les tables d'audit à INSERT seul (lot E4) retireront UPDATE et DELETE à
--  ged_app dans leur propre changeset (REVOKE ciblé) : les privilèges par
--  défaut ci-dessous sont le cas général.
--
--  À exécuter avec un superutilisateur, une fois par base :
--
--    psql -U postgres -d postgres -v base=ged -f preparer-base.sql
--
--  Variables :
--    base   (obligatoire) nom de la base ;
--    tests  (facultative, valeur `oui`) base de TEST uniquement : donne à
--           ged_owner le droit de créer des schémas dans la base, pour que la
--           suite de tests puisse dérouler toutes les migrations puis leur
--           retour arrière dans un schéma jetable.
-- =====================================================================

\set ON_ERROR_STOP on

\if :{?base}
\else
  \echo 'Variable manquante : -v base=<nom de la base>'
  \quit
\endif

-- Les rôles doivent exister : ce script ne les crée pas (creer-roles.sql).
DO $$
BEGIN
    IF (SELECT count(*) FROM pg_roles WHERE rolname IN ('ged_owner', 'ged_app', 'ged_readonly')) <> 3 THEN
        RAISE EXCEPTION 'Rôles ged_owner, ged_app, ged_readonly absents : exécuter d''abord creer-roles.sql';
    END IF;
END
$$;

-- ---------- Base ----------

SELECT format('CREATE DATABASE %I ENCODING %L TEMPLATE template0', :'base', 'UTF8')
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'base') \gexec

-- Personne d'autre que les trois rôles (et l'administrateur du serveur) ne se
-- connecte à la base.
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'base') \gexec
SELECT format('GRANT CONNECT, TEMPORARY ON DATABASE %I TO ged_owner, ged_app, ged_readonly', :'base') \gexec

\if :{?tests}
SELECT format('GRANT CREATE ON DATABASE %I TO ged_owner', :'base') \gexec
\endif

\connect :"base"

-- Le schéma `public` n'est utilisé par personne : on y retire toute création
-- (déjà le cas par défaut depuis PostgreSQL 15, réaffirmé ici).
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- ---------- Schémas ----------

CREATE SCHEMA IF NOT EXISTS ged AUTHORIZATION ged_owner;
CREATE SCHEMA IF NOT EXISTS ged_liquibase AUTHORIZATION ged_owner;
ALTER SCHEMA ged OWNER TO ged_owner;
ALTER SCHEMA ged_liquibase OWNER TO ged_owner;

GRANT USAGE ON SCHEMA ged TO ged_app, ged_readonly;
GRANT USAGE ON SCHEMA ged_liquibase TO ged_readonly;

-- ---------- Privilèges sur les objets créés par Liquibase ----------
-- Les privilèges « par défaut » s'appliquent aux objets que ged_owner créera :
-- chaque nouvelle table livrée par un changeset est donc aussitôt utilisable
-- par l'application, sans GRANT à écrire dans chaque migration.

ALTER DEFAULT PRIVILEGES FOR ROLE ged_owner IN SCHEMA ged
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ged_app;
ALTER DEFAULT PRIVILEGES FOR ROLE ged_owner IN SCHEMA ged
    GRANT USAGE, SELECT ON SEQUENCES TO ged_app;
ALTER DEFAULT PRIVILEGES FOR ROLE ged_owner IN SCHEMA ged
    GRANT SELECT ON TABLES TO ged_readonly;
ALTER DEFAULT PRIVILEGES FOR ROLE ged_owner IN SCHEMA ged
    GRANT SELECT ON SEQUENCES TO ged_readonly;
ALTER DEFAULT PRIVILEGES FOR ROLE ged_owner IN SCHEMA ged_liquibase
    GRANT SELECT ON TABLES TO ged_readonly;

-- Rejeu sur une base déjà peuplée : les objets existants reçoivent les mêmes
-- droits que ceux qui seront créés.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ged TO ged_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA ged TO ged_app;
GRANT SELECT ON ALL TABLES IN SCHEMA ged TO ged_readonly;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA ged TO ged_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA ged_liquibase TO ged_readonly;

-- Chemin de recherche : les requêtes non qualifiées visent le schéma `ged`.
SELECT format('ALTER ROLE ged_owner IN DATABASE %I SET search_path = ged', :'base') \gexec
SELECT format('ALTER ROLE ged_app IN DATABASE %I SET search_path = ged', :'base') \gexec
SELECT format('ALTER ROLE ged_readonly IN DATABASE %I SET search_path = ged', :'base') \gexec

\echo 'Base prête : schémas ged et ged_liquibase, droits de ged_owner, ged_app et ged_readonly.'
