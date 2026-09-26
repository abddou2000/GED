-- =====================================================================
--  GED Marchica Med — rôles PostgreSQL et schémas (dossier technique §4.2.3)
-- =====================================================================
--  Trois rôles distincts, aucun superutilisateur :
--    ged_owner    propriétaire des schémas ; utilisé UNIQUEMENT par Liquibase
--                 au déploiement, jamais par l'application en exécution ;
--    ged_app      compte d'exécution de l'application : DML seulement
--                 (SELECT, INSERT, UPDATE, DELETE), aucun DDL ;
--    ged_readonly lecture seule (diagnostic, supervision).
--
--  Deux schémas, tous deux propriété de ged_owner :
--    ged            tables applicatives ;
--    ged_liquibase  registre des migrations (DATABASECHANGELOG et son verrou).
--                   Séparé pour que ged_app ne puisse ni lire ni réécrire
--                   l'historique des migrations : les privilèges par défaut
--                   accordés à ged_app ne portent que sur le schéma `ged`.
--
--  À exécuter avec un superutilisateur, une fois par base (idempotent : peut
--  être rejoué sans effet de bord) :
--
--    psql -U postgres -d postgres -v base=ged \
--         -v mdp_owner='...' -v mdp_app='...' -v mdp_readonly='...' \
--         -f creer-roles.sql
--
--  Variables :
--    base          (obligatoire) base cible, créée si elle n'existe pas ;
--    mdp_owner, mdp_app, mdp_readonly
--                  (facultatives) mots de passe. Fournies, elles sont posées
--                  (création ou mise à jour). Absentes, le rôle est créé sans
--                  mot de passe : il n'est alors utilisable qu'avec une
--                  authentification locale `trust`/`peer` — poste de
--                  développement uniquement. En UAT et en production, les trois
--                  sont OBLIGATOIRES et viennent du coffre de MMED, jamais d'un
--                  fichier versionné ;
--    tests         (facultative, valeur `oui`) base de TEST uniquement : donne à
--                  ged_owner le droit de créer des schémas dans la base, pour
--                  que la suite de tests puisse dérouler toutes les migrations
--                  puis leur retour arrière dans un schéma jetable.
--
--  Les rôles sont globaux au serveur PostgreSQL : plusieurs bases (dev, test,
--  UAT) sur un même serveur partagent donc ged_owner, ged_app et ged_readonly.
-- =====================================================================

\set ON_ERROR_STOP on

\if :{?base}
\else
  \echo 'Variable manquante : -v base=<nom de la base>'
  \quit
\endif

-- ---------- Rôles (niveau serveur) ----------

SELECT 'CREATE ROLE ged_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ged_owner') \gexec
SELECT 'CREATE ROLE ged_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ged_app') \gexec
SELECT 'CREATE ROLE ged_readonly LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ged_readonly') \gexec

\if :{?mdp_owner}
SELECT format('ALTER ROLE ged_owner PASSWORD %L', :'mdp_owner') \gexec
\endif
\if :{?mdp_app}
SELECT format('ALTER ROLE ged_app PASSWORD %L', :'mdp_app') \gexec
\endif
\if :{?mdp_readonly}
SELECT format('ALTER ROLE ged_readonly PASSWORD %L', :'mdp_readonly') \gexec
\endif

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

\echo 'Rôles ged_owner, ged_app, ged_readonly et schémas ged, ged_liquibase prêts.'
