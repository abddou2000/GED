#!/usr/bin/env node
/*
 * Génère docs/modelisation/SCHEMA-BASE.md (P-05, DAT §4.5, §12.1) : schéma de
 * base détaillé LU DANS une base créée par Liquibase — tables, colonnes, types,
 * contraintes, index — avec la volumétrie estimée d'après les hypothèses du
 * §6.6 et un diagramme entité-association (Mermaid) par groupe du §12.1.
 *
 * Le document décrit la base réelle, pas une intention : il est à régénérer
 * après chaque changeset.
 *
 * Usage :
 *   node outils/schema-base.mjs [--base ged_dev2_test] [--schema ged]
 *        [--registre ged_liquibase] [--psql "C:/Program Files/PostgreSQL/16/bin/psql.exe"]
 *        [--utilisateur postgres] [--hote localhost] [--port 5432]
 *
 * La base visée doit avoir été migrée par Liquibase : la base de test l'est à
 * chaque `mvn test` (drop-first), une base de développement au démarrage.
 * Lecture seule : le script n'exécute que des SELECT sur les catalogues.
 */
import { execFileSync } from 'node:child_process';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const racine = join(dirname(fileURLToPath(import.meta.url)), '..');
const SORTIE = join(racine, 'docs/modelisation/SCHEMA-BASE.md');

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, t) => {
  if (a.startsWith('--')) acc.push([a.slice(2), t[i + 1]]);
  return acc;
}, []));
const BASE = args.base || process.env.DB_NAME_SCHEMA || 'ged_dev2_test';
const SCHEMA = args.schema || 'ged';
const REGISTRE = args.registre || 'ged_liquibase';
const PSQL = args.psql || process.env.PSQL
  || (process.platform === 'win32' ? 'C:/Program Files/PostgreSQL/16/bin/psql.exe' : 'psql');

/** Exécute une requête qui renvoie un seul document JSON. */
function json(sql) {
  const sortie = execFileSync(PSQL, ['-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1',
    '-h', args.hote || 'localhost', '-p', args.port || '5432', '-U', args.utilisateur || 'postgres',
    '-d', BASE, '-c', sql], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  return JSON.parse(sortie.trim() || 'null');
}

const s = SCHEMA.replace(/'/g, "''");
const PARTITION = "'^journal_audit_[0-9]{6}$'";

const jalons = json(`SELECT coalesce(json_agg(tag ORDER BY orderexecuted), '[]') FROM ${REGISTRE}.databasechangelog WHERE tag IS NOT NULL`);
const nbChangesets = json(`SELECT count(*) FROM ${REGISTRE}.databasechangelog`);

const tables = json(`
  SELECT coalesce(json_agg(t ORDER BY t.nom), '[]') FROM (
    SELECT c.relname AS nom,
           obj_description(c.oid) AS commentaire,
           c.relkind = 'p' AS partitionnee,
           (SELECT json_agg(json_build_object(
                'nom', a.attname,
                'type', format_type(a.atttypid, a.atttypmod),
                'nul', NOT a.attnotnull,
                'defaut', pg_get_expr(d.adbin, d.adrelid),
                'largeur', CASE WHEN a.attlen > 0 THEN a.attlen ELSE NULL END)
              ORDER BY a.attnum)
              FROM pg_attribute a
              LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
             WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped) AS colonnes,
           (SELECT json_agg(json_build_object('nom', k.conname, 'type', k.contype,
                'definition', pg_get_constraintdef(k.oid),
                'cible', CASE WHEN k.contype = 'f' THEN k.confrelid::regclass::text END)
              ORDER BY k.contype, k.conname)
              FROM pg_constraint k WHERE k.conrelid = c.oid AND k.contype IN ('p', 'u', 'f', 'c')) AS contraintes,
           (SELECT json_agg(json_build_object('nom', i.relname, 'definition', pg_get_indexdef(i.oid))
              ORDER BY i.relname)
              FROM pg_index x JOIN pg_class i ON i.oid = x.indexrelid
             WHERE x.indrelid = c.oid AND NOT x.indisprimary
               AND NOT EXISTS (SELECT 1 FROM pg_constraint k WHERE k.conindid = i.oid)) AS index
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = '${s}' AND c.relkind IN ('r', 'p') AND NOT c.relispartition
       AND c.relname !~ ${PARTITION}) t`);

/* ------------------------------------------------------------------ §12.1 */
const GROUPES = [
  ['Identités et accès', ['utilisateur', 'cache_annuaire', 'session', 'application', 'cle_api', 'cle_api_portee', 'employe']],
  ['Habilitations', ['role', 'permission', 'role_permission', 'groupe_ged', 'groupe_membre', 'habilitation', 'version_habilitations']],
  ['Organisation documentaire', ['noeud', 'document', 'document_rattachement', 'document_confidentiel_designe', 'etiquette', 'document_etiquette']],
  ['Typologie', ['type_document', 'index_def', 'plan_indexation', 'plan_index', 'document_index_valeur']],
  ['Versions et contenu', ['version_document', 'cle_fichier', 'document_texte', 'ocr_job', 'copie_conservation']],
  ['Circuits de validation', ['regle_workflow', 'regle_validateur', 'circuit', 'circuit_validateur', 'decision', 'workflow_ged', 'workflow_ged_etape', 'workflow_ged_signature']],
  ['Traçabilité et exploitation', ['journal_audit', 'journal_audit_scellement', 'notification', 'preference_notification', 'idempotence_cle', 'job_archivage', 'job_archivage_element', 'job_export', 'job_export_element']],
];
const groupeDe = new Map(GROUPES.flatMap(([g, ts]) => ts.map(t => [t, g])));

/* -------------------------------------------------- volumétrie (§6.6) ---- */
// Hypothèses du DAT §6.6 : reprise 150 000 documents + 60 000 par an (450 000 à
// 5 ans), 8 pages par document, facteur 1,3 de versions, 3 Ko de texte par page,
// 5 millions d'événements d'audit par an (0,5 Ko chacun).
const DOCS_5ANS = 450_000;
const VOLUMES = {
  document: [DOCS_5ANS, 'reprise 150 000 + 60 000 / an'],
  version_document: [DOCS_5ANS * 1.3, 'facteur 1,3 de versions'],
  cle_fichier: [DOCS_5ANS * 1.3 + 50_000, 'une par version, plus copies PDF/A et aperçus'],
  document_texte: [DOCS_5ANS, 'texte de la version courante (8 pages × 3 Ko) et son vecteur tsv', 8 * 3 * 1024 * 2],
  ocr_job: [DOCS_5ANS * 1.3, 'un par version à OCRiser'],
  document_index_valeur: [DOCS_5ANS * 5, 'environ 5 index renseignés par document'],
  document_etiquette: [DOCS_5ANS * 0.5, 'une étiquette pour un document sur deux'],
  document_rattachement: [DOCS_5ANS * 0.1, 'un rattachement pour 10 % des documents'],
  journal_audit: [25_000_000, '5 millions d\'événements par an, 0,5 Ko', 512],
  journal_audit_scellement: [5 * 365 * 24, 'un scellement par heure'],
  notification: [DOCS_5ANS * 2, 'circuits, accès, échéances : environ 2 par document'],
  idempotence_cle: [60_000 / 250 * 2, 'fenêtre de 24 h : créations d\'une journée de pointe'],
  copie_conservation: [DOCS_5ANS * 0.3, 'documents archivés (hypothèse 30 %)'],
  job_archivage_element: [DOCS_5ANS * 0.3, 'une ligne par document archivé en masse'],
  job_export_element: [DOCS_5ANS * 0.2, 'exports de dossiers'],
  session: [5_000, 'sessions de 5 ans purgées ; ordre de grandeur'],
  noeud: [20_000, 'espaces et dossiers'],
  habilitation: [20_000, 'attributions'],
};

/** Largeur estimée d'une ligne (octets) d'après les types des colonnes. */
function largeur(t) {
  let n = 24; // en-tête de ligne PostgreSQL
  for (const c of t.colonnes || []) {
    if (c.largeur) { n += c.largeur; continue; }
    const m = /character varying\((\d+)\)|character\((\d+)\)/.exec(c.type);
    if (m) n += Math.min(Number(m[1] || m[2]) / 3, 80);
    else if (/text|jsonb|bytea/.test(c.type)) n += 200;
    else n += 16;
  }
  return Math.round(n);
}
const lisible = o => o >= 1e9 ? (o / 1e9).toFixed(1) + ' Go' : o >= 1e6 ? (o / 1e6).toFixed(1) + ' Mo' : Math.max(1, Math.round(o / 1e3)) + ' Ko';

/* ---------------------------------------------------------------- rendu */
const md = [];
md.push('# Schéma de la base de données (P-05, DAT §4.5, §12.1)', '');
md.push(`> Généré par \`node outils/schema-base.mjs\` depuis la base \`${BASE}\`, schéma \`${SCHEMA}\`,`
  + ` migrée par Liquibase (${nbChangesets} changesets ; jalons : ${jalons.map(j => '`' + j + '`').join(', ')}).`
  + ' Ne pas modifier à la main : régénérer après chaque changeset.', '');
md.push('Conventions (§4.2.2) : snake_case, clé primaire `id` UUID (sauf le journal d\'audit : `bigint` séquentiel,'
  + ' ordre du scellement chaîné), clés étrangères `<table>_id`, préfixes `pk_`, `uk_`, `fk_`, `ck_`, `idx_`.'
  + ' Les partitions mensuelles `journal_audit_AAAAMM` ne sont pas listées.', '');

md.push('## Synthèse et volumétrie estimée à 5 ans (§6.6)', '');
md.push('| Groupe (§12.1) | Table | Colonnes | Lignes à 5 ans | Taille estimée | Base de l\'estimation |');
md.push('|---|---|---|---|---|---|');
let total = 0;
for (const t of [...tables].sort((a, b) => (groupeDe.get(a.nom) || 'zz').localeCompare(groupeDe.get(b.nom) || 'zz') || a.nom.localeCompare(b.nom))) {
  const v = VOLUMES[t.nom];
  const lignes = v ? Math.round(v[0]) : null;
  const octets = lignes ? lignes * (v[2] || largeur(t)) : null;
  if (octets) total += octets;
  md.push(`| ${groupeDe.get(t.nom) || 'Autres (historique)'} | \`${t.nom}\` | ${(t.colonnes || []).length} | `
    + `${lignes ? lignes.toLocaleString('fr-FR') : 'référentiel (< 10 000)'} | ${octets ? lisible(octets) : '< 10 Mo'} | ${v ? v[1] : '—'} |`);
}
md.push('', `Total estimé des tables volumineuses : **${lisible(total)}** y compris le vecteur plein texte, hors index et WAL ;`
  + ' le §6.6 retient ≈ 40 Go de base à 5 ans (texte ≈ 11 Go, index plein texte ≈ 11 Go, audit ≈ 12 Go)'
  + ' et 200 Go à provisionner.', '');

md.push('## Diagrammes entité-association par groupe', '');
for (const [groupe, noms] of GROUPES) {
  const presentes = tables.filter(t => noms.includes(t.nom));
  if (!presentes.length) continue;
  md.push(`### ${groupe}`, '', '```mermaid', 'erDiagram');
  const liens = new Set();
  for (const t of presentes) {
    md.push(`  ${t.nom} {`);
    for (const c of t.colonnes || []) {
      const cle = (t.contraintes || []).some(k => k.type === 'p' && k.definition.includes(`(${c.nom})`)) ? ' PK'
        : (t.contraintes || []).some(k => k.type === 'f' && k.definition.startsWith(`FOREIGN KEY (${c.nom})`)) ? ' FK' : '';
      md.push(`    ${c.type.replace(/[^A-Za-z0-9_]/g, '_')} ${c.nom}${cle}`);
    }
    md.push('  }');
    for (const k of (t.contraintes || []).filter(k => k.type === 'f')) {
      const cible = k.cible.replace(/^.*\./, '');
      const col = /FOREIGN KEY \(([^)]+)\)/.exec(k.definition)[1];
      liens.add(`  ${cible} ||--o{ ${t.nom} : "${col}"`);
    }
  }
  md.push(...liens, '```', '');
  const externes = [...liens].map(l => l.trim().split(' ')[0]).filter(n => !noms.includes(n));
  if (externes.length) md.push(`Tables d'autres groupes référencées : ${[...new Set(externes)].map(n => '`' + n + '`').join(', ')}.`, '');
}

md.push('## Détail des tables', '');
for (const t of tables) {
  md.push(`### \`${t.nom}\`${t.partitionnee ? ' (partitionnée par mois)' : ''}`, '');
  if (t.commentaire) md.push(t.commentaire, '');
  md.push('| Colonne | Type | Nul | Défaut |', '|---|---|---|---|');
  for (const c of t.colonnes || []) {
    md.push(`| \`${c.nom}\` | ${c.type} | ${c.nul ? 'oui' : 'non'} | ${c.defaut ? '`' + c.defaut.replace(/\|/g, '\\|') + '`' : ''} |`);
  }
  md.push('');
  const k = t.contraintes || [];
  if (k.length) {
    md.push('Contraintes :', '');
    const libelle = { p: 'clé primaire', u: 'unicité', f: 'clé étrangère', c: 'vérification' };
    for (const c of k) md.push(`- \`${c.nom}\` (${libelle[c.type]}) : \`${c.definition.replace(/\s+/g, ' ')}\``);
    md.push('');
  }
  if ((t.index || []).length) {
    md.push('Index :', '');
    for (const i of t.index) md.push(`- \`${i.nom}\` : \`${i.definition.replace(/^CREATE (UNIQUE )?INDEX \S+ ON \S+ /, '')}\``);
    md.push('');
  }
}

mkdirSync(dirname(SORTIE), { recursive: true });
writeFileSync(SORTIE, md.join('\n') + '\n', 'utf8');
console.log(`Schéma écrit : ${SORTIE} (${tables.length} tables)`);
