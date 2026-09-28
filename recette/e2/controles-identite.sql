-- Recette E2 — contrôles du schéma d'identité (lecture seule).
-- DAT V3 §3.2 (aucun référentiel local de mots de passe), §3.3 (provisionnement par
-- objectGUID), §3.4.1 (table session, empreinte du jeton de renouvellement), §3.4.2
-- (cache_annuaire), décisions D1 et D3.
-- Variables : schema. Sortie : controle|gravite|objet|detail (aucune ligne = OK).

\set ON_ERROR_STOP 1
SET client_min_messages = warning;
CREATE TEMP TABLE qa_constats (controle text, gravite text, objet text, detail text);

-- E2-C01 : aucune colonne dont le nom évoque un mot de passe ou son empreinte.
-- (Les empreintes de JETONS de session et de clés d'API sont légitimes : colonnes
-- « empreinte » de session / cle_api, exclues nommément.)
INSERT INTO qa_constats
SELECT 'E2-C01', 'ERREUR', c.table_name || '.' || c.column_name, 'colonne évoquant un mot de passe'
FROM information_schema.columns c
WHERE c.table_schema = :'schema'
  AND c.column_name ~* '(mot_?de_?passe|password|passwd|pwd|mdp|bcrypt|hash_?mdp|salt|sel_)';

-- E2-C02 : aucune table de comptes locaux.
INSERT INTO qa_constats
SELECT 'E2-C02', 'ERREUR', table_name, 'table de comptes locaux encore présente'
FROM information_schema.tables
WHERE table_schema = :'schema' AND table_name ~* '^(compte_utilisateur|comptes?|users?|app_user|credentials?)$';

-- E2-C03 : aucune VALEUR ressemblant à une empreinte de mot de passe (BCrypt, Argon2,
-- PBKDF2, SHA-crypt) dans toute colonne texte du schéma — une donnée reprise par
-- erreur ne se voit pas dans le catalogue.
SELECT set_config('qa.schema', :'schema', false) AS qa_g \gset
DO $$
DECLARE r record; n bigint;
BEGIN
  FOR r IN SELECT c.table_name, c.column_name FROM information_schema.columns c
           JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
           WHERE c.table_schema = current_setting('qa.schema') AND t.table_type = 'BASE TABLE'
             AND c.data_type IN ('text', 'character varying', 'character')
  LOOP
    EXECUTE format('SELECT count(*) FROM %I.%I WHERE %I ~ %L', current_setting('qa.schema'), r.table_name, r.column_name,
                   '^(\$2[abxy]?\$[0-9]{2}\$|\$argon2|\$pbkdf2|\$[56]\$|\{bcrypt\}|\{noop\}|\{SSHA)') INTO n;
    IF n > 0 THEN
      INSERT INTO qa_constats VALUES ('E2-C03', 'ERREUR', r.table_name || '.' || r.column_name, n || ' valeur(s) au format d''empreinte de mot de passe');
    END IF;
  END LOOP;
END $$;

-- E2-C04 : table utilisateur avec objectGUID unique (§3.3, D2)
INSERT INTO qa_constats
SELECT 'E2-C04', 'ERREUR', 'utilisateur', 'table absente'
WHERE to_regclass(quote_ident(:'schema') || '.utilisateur') IS NULL;
INSERT INTO qa_constats
SELECT 'E2-C04', 'ERREUR', 'utilisateur.object_guid', 'colonne ou contrainte d''unicité absente'
WHERE to_regclass(quote_ident(:'schema') || '.utilisateur') IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM pg_constraint k JOIN pg_class t ON t.oid = k.conrelid JOIN pg_namespace n ON n.oid = t.relnamespace
    JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY (k.conkey)
    WHERE n.nspname = :'schema' AND t.relname = 'utilisateur' AND k.contype IN ('u', 'p') AND a.attname = 'object_guid'
      AND array_length(k.conkey, 1) = 1
    UNION ALL
    SELECT 1 FROM pg_index i JOIN pg_class t ON t.oid = i.indrelid JOIN pg_namespace n ON n.oid = t.relnamespace
    JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = i.indkey[0]
    WHERE n.nspname = :'schema' AND t.relname = 'utilisateur' AND i.indisunique AND i.indnatts = 1 AND a.attname = 'object_guid');

-- E2-C05 : table session sans jeton en clair : une colonne d'empreinte, aucune colonne « jeton/token » brute
INSERT INTO qa_constats
SELECT 'E2-C05', 'ERREUR', 'session', 'table absente'
WHERE to_regclass(quote_ident(:'schema') || '.session') IS NULL;
INSERT INTO qa_constats
SELECT 'E2-C05', 'ERREUR', 'session.' || column_name, 'colonne susceptible de contenir le jeton en clair'
FROM information_schema.columns
WHERE table_schema = :'schema' AND table_name = 'session'
  AND column_name ~* '(jeton|token|refresh)' AND column_name !~* '(empreinte|hash|sha|famille|family)';
INSERT INTO qa_constats
SELECT 'E2-C05', 'ERREUR', 'session', 'aucune colonne d''empreinte'
WHERE to_regclass(quote_ident(:'schema') || '.session') IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = :'schema' AND table_name = 'session'
                  AND column_name ~* '(empreinte|hash|sha)');

-- E2-C06 : cache_annuaire présent (§3.4.2)
INSERT INTO qa_constats
SELECT 'E2-C06', 'ERREUR', 'cache_annuaire', 'table absente'
WHERE to_regclass(quote_ident(:'schema') || '.cache_annuaire') IS NULL;

-- E2-C07 : D1/D3 — aucune colonne d'état d'activation ni d'appartenance AD mémorisée
INSERT INTO qa_constats
SELECT 'E2-C07', 'ERREUR', table_name || '.' || column_name, 'attribut d''annuaire hors minimum (D1, D3, P2)'
FROM information_schema.columns
WHERE table_schema = :'schema' AND table_name IN ('utilisateur', 'cache_annuaire')
  AND column_name ~* '(user_?account_?control|member_?of|groupe_ad|unite_org|ou_|organizational)';

SELECT controle, gravite, objet, replace(detail, '|', '/') FROM qa_constats ORDER BY 1, 3;
