-- =====================================================================
--  GED Marchica Med — rôles PostgreSQL (dossier technique §4.2.3)
-- =====================================================================
--  Trois rôles distincts, aucun superutilisateur :
--    ged_owner    propriétaire des schémas ; utilisé UNIQUEMENT par Liquibase
--                 au déploiement, jamais par l'application en exécution ;
--    ged_app      compte d'exécution de l'application : DML seulement
--                 (SELECT, INSERT, UPDATE, DELETE), aucun DDL ;
--    ged_readonly lecture seule (diagnostic, supervision).
--
--  Les rôles sont GLOBAUX au serveur PostgreSQL : toutes les bases d'un même
--  serveur (dev, test, UAT) les partagent. Ce script ne fait donc que les
--  créer s'ils manquent. Il est idempotent et NE FAIT JAMAIS :
--    - de DROP ROLE ;
--    - de changement de mot de passe d'un rôle existant (une rotation est une
--      opération d'exploitation explicite : ALTER ROLE ... PASSWORD).
--  Les droits sur une base donnée sont accordés par preparer-base.sql.
--
--  À exécuter avec un superutilisateur, une fois par serveur :
--
--    psql -U postgres -d postgres --         -v mdp_owner='...' -v mdp_app='...' -v mdp_readonly='...' --         -f creer-roles.sql
--
--  Variables (facultatives) : mdp_owner, mdp_app, mdp_readonly.
--    Fournies, elles deviennent le mot de passe du rôle QUAND IL EST CRÉÉ.
--    Absentes, le rôle est créé sans mot de passe : il n'est alors utilisable
--    qu'avec une authentification locale `trust`/`peer` — poste de
--    développement uniquement. En UAT et en production, les trois sont
--    OBLIGATOIRES et viennent du coffre de MMED, jamais d'un fichier versionné.
-- =====================================================================

\set ON_ERROR_STOP on

\if :{?mdp_owner}
SELECT format('CREATE ROLE ged_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'mdp_owner')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ged_owner') \gexec
\else
SELECT 'CREATE ROLE ged_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ged_owner') \gexec
\endif
\if :{?mdp_app}
SELECT format('CREATE ROLE ged_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'mdp_app')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ged_app') \gexec
\else
SELECT 'CREATE ROLE ged_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ged_app') \gexec
\endif
\if :{?mdp_readonly}
SELECT format('CREATE ROLE ged_readonly LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'mdp_readonly')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ged_readonly') \gexec
\else
SELECT 'CREATE ROLE ged_readonly LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ged_readonly') \gexec
\endif

SELECT rolname AS role, rolcanlogin AS connexion, rolsuper AS superutilisateur
  FROM pg_roles WHERE rolname IN ('ged_owner', 'ged_app', 'ged_readonly') ORDER BY rolname;
