-- =====================================================================
--  GED Marchica Med — pgaudit par rôle (P-16)
--  À exécuter avec un superutilisateur APRÈS l'activation de pgaudit
--  (pgaudit.conf.exemple) :
--     psql -U postgres -d postgres -f pgaudit-roles.sql
--  Idempotent. Les réglages de rôle s'appliquent aux NOUVELLES sessions.
-- =====================================================================
\set ON_ERROR_STOP on

CREATE EXTENSION IF NOT EXISTS pgaudit;

-- Superutilisateur : tout ce qu'il fait est tracé (lecture comprise), c'est
-- le seul compte qui puisse contourner les protections du journal d'audit.
ALTER ROLE postgres SET pgaudit.log = 'all';

-- Propriétaire du schéma (déploiements Liquibase, interventions) : DDL,
-- droits, écritures et fonctions. Pas les lectures (volume, peu d'intérêt).
ALTER ROLE ged_owner SET pgaudit.log = 'ddl, role, write, function, misc_set';

-- Lecture seule (diagnostic) : toute lecture d'une personne est tracée.
ALTER ROLE ged_readonly SET pgaudit.log = 'read';

-- Compte de l'application : rien de plus (journal d'audit applicatif).
ALTER ROLE ged_app SET pgaudit.log = 'none';

-- Vérification : réglages par rôle.
SELECT rolname, rolconfig FROM pg_roles
 WHERE rolname IN ('postgres', 'ged_owner', 'ged_readonly', 'ged_app') ORDER BY 1;
