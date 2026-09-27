-- Autotest des contrôles E1 : schéma CONFORME au DAT V3 (§4.2.2, §4.2.3, §12.1, §12.5, §12.7).
-- Sert à prouver que verifier-socle.sh ne signale RIEN sur un schéma correct.
-- Rôles préfixés qa_ : les rôles PostgreSQL sont communs à tout le serveur, on ne
-- touche jamais aux vrais ged_owner / ged_app / ged_readonly livrés par dev1.

\set ON_ERROR_STOP 1
SET client_min_messages = warning;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'qa_ged_owner') THEN CREATE ROLE qa_ged_owner NOLOGIN; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'qa_ged_app') THEN CREATE ROLE qa_ged_app NOLOGIN; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'qa_ged_readonly') THEN CREATE ROLE qa_ged_readonly NOLOGIN; END IF;
END $$;

CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

DROP SCHEMA IF EXISTS recette_ok CASCADE;
CREATE SCHEMA recette_ok AUTHORIZATION qa_ged_owner;
REVOKE ALL ON SCHEMA recette_ok FROM PUBLIC;
GRANT USAGE ON SCHEMA recette_ok TO qa_ged_app, qa_ged_readonly;

SET ROLE qa_ged_owner;
SET search_path = recette_ok;

ALTER DEFAULT PRIVILEGES IN SCHEMA recette_ok GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO qa_ged_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA recette_ok GRANT SELECT ON TABLES TO qa_ged_readonly;

CREATE TABLE utilisateur (
  id          uuid        CONSTRAINT pk_utilisateur PRIMARY KEY DEFAULT gen_random_uuid(),
  object_guid uuid        NOT NULL CONSTRAINT uk_utilisateur_object_guid UNIQUE,
  login       text        NOT NULL,
  cree_le     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE noeud (
  id           uuid        CONSTRAINT pk_noeud PRIMARY KEY DEFAULT gen_random_uuid(),
  parent_id    uuid        CONSTRAINT fk_noeud_parent REFERENCES noeud (id),
  chemin       text        NOT NULL,
  nom          text        NOT NULL,
  nature       text        NOT NULL CONSTRAINT ck_noeud_nature CHECK (nature IN ('ESPACE', 'DOSSIER')),
  supprime     boolean     NOT NULL DEFAULT false,
  supprime_par uuid        CONSTRAINT fk_noeud_supprime_par REFERENCES utilisateur (id),
  supprime_le  timestamptz
);
CREATE INDEX idx_noeud_parent_id ON noeud (parent_id);
CREATE INDEX idx_noeud_supprime_par ON noeud (supprime_par);
CREATE INDEX idx_noeud_chemin ON noeud (chemin text_pattern_ops);

CREATE TABLE document (
  id                 uuid        CONSTRAINT pk_document PRIMARY KEY DEFAULT gen_random_uuid(),
  noeud_principal_id uuid        NOT NULL CONSTRAINT fk_document_noeud_principal REFERENCES noeud (id),
  nom                text        NOT NULL,
  confidentialite    text        NOT NULL DEFAULT 'PUBLIC'
                                 CONSTRAINT ck_document_confidentialite CHECK (confidentialite IN ('PUBLIC', 'PRIVE', 'CONFIDENTIEL')),
  metadonnees        jsonb       NOT NULL DEFAULT '{}'::jsonb,
  cree_le            timestamptz NOT NULL DEFAULT now(),
  supprime           boolean     NOT NULL DEFAULT false,
  supprime_par       uuid        CONSTRAINT fk_document_supprime_par REFERENCES utilisateur (id),
  supprime_le        timestamptz
);
CREATE INDEX idx_document_noeud_principal_id ON document (noeud_principal_id);
CREATE INDEX idx_document_supprime_par ON document (supprime_par);
CREATE INDEX idx_document_metadonnees ON document USING gin (metadonnees jsonb_path_ops);

CREATE TABLE version_document (
  id          uuid        CONSTRAINT pk_version_document PRIMARY KEY DEFAULT gen_random_uuid(),
  document_id uuid        NOT NULL CONSTRAINT fk_version_document_document REFERENCES document (id),
  numero      integer     NOT NULL,
  courante    boolean     NOT NULL,
  empreinte   char(64)    NOT NULL,
  auteur_id   uuid        NOT NULL CONSTRAINT fk_version_document_auteur REFERENCES utilisateur (id),
  cree_le     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uk_version_document_numero UNIQUE (document_id, numero)
);
-- Une seule version courante par document (§12.8) : index unique partiel autonome.
CREATE UNIQUE INDEX uk_version_document_courante ON version_document (document_id) WHERE courante;
CREATE INDEX idx_version_document_auteur_id ON version_document (auteur_id);

-- Journal d'audit partitionné par mois (§7.4.1, §7.4.3) : identifiant séquentiel et
-- clé primaire incluant la clé de partition — écarts justifiés dans exceptions-autotest.txt.
CREATE TABLE journal_audit (
  id                    bigint      GENERATED ALWAYS AS IDENTITY,
  horodatage            timestamptz NOT NULL DEFAULT now(),
  acteur_utilisateur_id uuid,
  action                text        NOT NULL,
  CONSTRAINT pk_journal_audit PRIMARY KEY (id, horodatage)
) PARTITION BY RANGE (horodatage);
CREATE TABLE journal_audit_2026_09 PARTITION OF journal_audit FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
REVOKE UPDATE, DELETE, TRUNCATE ON journal_audit, journal_audit_2026_09 FROM qa_ged_app;

-- Journal Liquibase tel que Liquibase 4 le crée (colonnes en minuscules sous PostgreSQL).
CREATE TABLE databasechangelog (
  id varchar(255) NOT NULL, author varchar(255) NOT NULL, filename varchar(255) NOT NULL,
  dateexecuted timestamp NOT NULL, orderexecuted integer NOT NULL, exectype varchar(10) NOT NULL,
  md5sum varchar(35), description varchar(255), comments varchar(255), tag varchar(255),
  liquibase varchar(20), contexts varchar(255), labels varchar(255), deployment_id varchar(10)
);
CREATE TABLE databasechangeloglock (
  id integer NOT NULL CONSTRAINT pk_databasechangeloglock PRIMARY KEY, locked boolean NOT NULL,
  lockgranted timestamp, lockedby varchar(255)
);
REVOKE INSERT, UPDATE, DELETE ON databasechangelog, databasechangeloglock FROM qa_ged_app;
INSERT INTO databasechangelog (id, author, filename, dateexecuted, orderexecuted, exectype, contexts, labels) VALUES
  ('202610011200-1', 'dev1', 'db/changelog/changes/202610011200_creation_table_utilisateur.xml', now(), 1, 'EXECUTED', NULL, NULL),
  ('202610011205-1', 'dev1', 'db/changelog/changes/202610011205_creation_table_noeud.xml', now(), 2, 'EXECUTED', NULL, NULL),
  ('202610011210-1', 'dev1', 'db/changelog/changes/202610011210_donnees_niveaux_confidentialite.xml', now(), 3, 'EXECUTED', 'data-initial', 'data-initial');

RESET ROLE;
RESET search_path;
