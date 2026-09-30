-- =====================================================================
--  GED Marchica Med — pgaudit par rôle (P-16)
--  À exécuter avec un superutilisateur APRÈS l'activation de pgaudit
--  (activer-pgaudit.sh, pgaudit.conf.exemple, redémarrage) :
--     psql -U postgres -d postgres -f pgaudit-roles.sql
--  Idempotent. À RELANCER après la création de chaque compte nominatif
--  d'administrateur de base. Les réglages s'appliquent aux NOUVELLES sessions.
--
--  Un réglage ALTER ROLE ne s'hérite pas d'un groupe : il vaut pour le compte
--  de CONNEXION. D'où le parcours des membres de ged_dba ci-dessous, et le
--  réglage par défaut de pgaudit.conf, qui trace tout compte oublié ici.
-- =====================================================================
\set ON_ERROR_STOP on

CREATE EXTENSION IF NOT EXISTS pgaudit;

-- Groupe des administrateurs de base NOMINATIFS (un compte par personne,
-- jamais partagé) : sans droit par lui-même, il sert à les désigner.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ged_dba') THEN
        CREATE ROLE ged_dba NOLOGIN;
    END IF;
END $$;

DO $$
DECLARE
    r record;
BEGIN
    -- Superutilisateurs et administrateurs nominatifs : tout est tracé, lectures
    -- comprises. Ce sont les seuls comptes qui puissent contourner les
    -- protections du journal d'audit ou lire le texte extrait des documents.
    FOR r IN
        SELECT rolname FROM pg_roles WHERE rolsuper AND rolcanlogin
        UNION
        SELECT m.rolname FROM pg_auth_members am
          JOIN pg_roles g ON g.oid = am.roleid AND g.rolname = 'ged_dba'
          JOIN pg_roles m ON m.oid = am.member
         WHERE m.rolcanlogin
    LOOP
        EXECUTE format('ALTER ROLE %I SET pgaudit.log = %L', r.rolname, 'all');
    END LOOP;

    -- Un superutilisateur peut couper pgaudit dans SA session (SET pgaudit.log
    -- = 'none'), et pgaudit ne trace pas ce SET (vérifié : essai-pgaudit.sh).
    -- log_statement écrit chaque requête à sa réception, AVANT exécution : le
    -- SET qui couperait la trace y figure, ainsi que tout ce qui suit.
    FOR r IN SELECT rolname FROM pg_roles WHERE rolsuper AND rolcanlogin
    LOOP
        EXECUTE format('ALTER ROLE %I SET log_statement = %L', r.rolname, 'all');
    END LOOP;

    -- Propriétaire du schéma (déploiements Liquibase) : DDL, droits, écritures,
    -- fonctions, SET (dont SET ROLE). Pas les lectures (volume, peu d'intérêt).
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ged_owner') THEN
        ALTER ROLE ged_owner SET pgaudit.log = 'ddl, role, write, function, misc_set';
    END IF;

    -- Lecture seule (diagnostic) et sauvegarde (pg_read_all_data, pg_dump) :
    -- toute lecture est tracée — la sauvegarde lit le texte extrait des
    -- documents ; une lecture hors des heures de sauvegarde se voit.
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ged_readonly') THEN
        ALTER ROLE ged_readonly SET pgaudit.log = 'read, misc_set';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ged_sauvegarde') THEN
        ALTER ROLE ged_sauvegarde SET pgaudit.log = 'read, misc_set';
    END IF;

    -- Compte de l'application : seule exemption (journal d'audit applicatif).
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ged_app') THEN
        ALTER ROLE ged_app SET pgaudit.log = 'none';
    END IF;
END $$;

-- Contrôle : chaque compte de connexion et son réglage effectif. Toute ligne
-- « none » autre que ged_app, ou un DBA qui n'est pas à « all », est une
-- anomalie à corriger avant la mise en service.
SELECT r.rolname,
       coalesce((SELECT substr(c, length('pgaudit.log=') + 1)
                   FROM unnest(r.rolconfig) c WHERE c LIKE 'pgaudit.log=%'),
                (SELECT setting FROM pg_file_settings
                  WHERE name = 'pgaudit.log' AND applied) || ' (défaut)',
                'none (aucun défaut : pgaudit.conf absent)') AS pgaudit_log,
       r.rolsuper AS superutilisateur,
       pg_has_role(r.oid, 'ged_dba', 'MEMBER') AS dba
  FROM pg_roles r
 WHERE r.rolcanlogin
 ORDER BY 1;
