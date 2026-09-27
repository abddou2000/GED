#!/usr/bin/env node
/*
 * Génère docs/modelisation/CLASSES.md (P-05, DAT §4.5) : un diagramme de classes
 * Mermaid par module (paquet de premier niveau de com.ipt.ged), LU DANS LE CODE.
 *
 * Contenu par module : classes, interfaces, énumérations et records publics de
 * premier niveau ; champs (dépendances injectées ou colonnes d'entité) ;
 * héritage et implémentation ; associations vers les autres types du projet.
 * Les DTO sont regroupés dans une note, pour garder les diagrammes lisibles.
 *
 * Usage : node outils/diagrammes-classes.mjs
 * Aucune dépendance : lecture des sources par expressions régulières (les
 * sources suivent les conventions du projet : un type public par fichier).
 */
import { readFileSync, readdirSync, statSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const racine = join(dirname(fileURLToPath(import.meta.url)), '..');
const SOURCES = join(racine, 'backend/src/main/java/com/ipt/ged');
const SORTIE = join(racine, 'docs/modelisation/CLASSES.md');

/** Modules décrits, avec leur lot et leur rôle. */
const MODULES = {
  identite: 'Identité et sessions (E2) : connexion par l\'annuaire, jetons, cache d\'annuaire',
  security: 'Chaîne de sécurité des utilisateurs (E2)',
  autorisation: 'Autorisation (E3) : habilitations, rôles, point d\'application unique',
  accessgroup: 'Groupes GED (E3)',
  workspace: 'Nœuds : espaces et dossiers (E1, E3)',
  document: 'Documents, versions, rattachements (E1, E5)',
  depot: 'Dépôt en deux temps (E5, §12.11)',
  fichier: 'Stockage chiffré, contrôles, antivirus, aperçu (E5)',
  ocr: 'OCR asynchrone (E6)',
  recherche: 'Recherche plein texte (E6)',
  indexation: 'Indexation et recherche multicritère (E1, E6)',
  cycledevie: 'Cycle de vie : archivage, purge, export (E7)',
  workflow: 'Règles de workflow (E1, E8)',
  signature: 'Circuits de validation actuels (E1)',
  notification: 'Notifications (E8, §12.9)',
  audit: 'Journal d\'audit (E4, §7.4)',
  cleapi: 'Clés d\'API, portée et délégation (E9, §5.4, §5.5)',
  idempotence: 'Idempotence des créations (E9, §5.3.2)',
  conventionsapi: 'Conventions de l\'API (E9, §5.3.2)',
  contratapi: 'Chemins du contrat d\'API (§5.3.1)',
  documentationapi: 'Spécification OpenAPI (§5.3)',
  supervision: 'Sondes et métriques (E10, §6.7)',
  journalisation: 'Journalisation technique (E10, §7.1)',
  securite: 'Contrôles de sécurité transverses (E11)',
};

function fichiers(dossier) {
  return readdirSync(dossier).flatMap(n => {
    const p = join(dossier, n);
    return statSync(p).isDirectory() ? fichiers(p) : p.endsWith('.java') ? [p] : [];
  });
}

const TYPE = /public\s+(?:final\s+|abstract\s+|sealed\s+|non-sealed\s+)*(class|interface|enum|record)\s+(\w+)(?:<[^{]*?>)?\s*(\([^)]*\))?\s*(?:extends\s+([\w.<>, ]+?))?\s*(?:implements\s+([\w.<>, ]+?))?\s*(?:permits\s+[\w., ]+)?\s*\{/;
const CHAMP = /^\s{4}(?:@\w+(?:\([^)]*\))?\s+)*(?:private|protected|public)\s+(?:final\s+)?(?!static)([\w.<>, ?\[\]]+?)\s+(\w+)\s*(?:=|;)/gm;

function lire(p) {
  const code = readFileSync(p, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
  const m = TYPE.exec(code);
  if (!m) return null;
  const [, genre, nom, composants, parent, interfaces] = m;
  const champs = [];
  if (composants) {
    for (const c of composants.slice(1, -1).split(/,(?![^<]*>)/)) {
      const parts = c.trim().replace(/@\w+(\([^)]*\))?\s*/g, '').split(/\s+/);
      if (parts.length >= 2) champs.push([parts.slice(0, -1).join(' '), parts.at(-1)]);
    }
  }
  const corps = code.slice(m.index + m[0].length);
  // Champs du type lui-même : premier niveau d'accolades seulement.
  let niveau = 0, premier = '';
  for (const ch of corps) {
    if (ch === '{') niveau++;
    else if (ch === '}') { if (niveau === 0) break; niveau--; }
    else if (niveau === 0) premier += ch;
    if (ch === '\n' && niveau === 0) premier += '';
  }
  for (const f of premier.matchAll(CHAMP)) champs.push([f[1].trim(), f[2]]);
  const entite = /@Entity/.test(code);
  return {
    genre, nom, entite,
    parent: parent ? parent.replace(/<.*>/, '').trim() : null,
    interfaces: interfaces ? interfaces.split(',').map(i => i.replace(/<.*>/, '').trim()).filter(Boolean) : [],
    champs, dto: /[\\/]dto[\\/]/.test(p) || /(Request|Response|Vue|Dto\w*)$/.test(nom),
  };
}

const parModule = new Map();
for (const p of fichiers(SOURCES)) {
  const module = relative(SOURCES, p).split(/[\\/]/)[0];
  if (!MODULES[module]) continue;
  const t = lire(p);
  if (t) (parModule.get(module) || parModule.set(module, []).get(module)).push(t);
}
const tousLesTypes = new Set([...parModule.values()].flat().map(t => t.nom));
const simple = type => type.replace(/.*\./, '').replace(/[<>\[\], ?]+/g, ' ').trim().split(' ');

const md = ['# Diagrammes de classes par module (P-05, DAT §4.5)', '',
  '> Générés par `node outils/diagrammes-classes.mjs` depuis `backend/src/main/java`. Ne pas modifier à la main.',
  '> Flèches : héritage (`<|--`), implémentation (`<|..`), dépendance ou association vers un type du projet (`-->`).',
  '> Les DTO (requêtes et réponses) sont listés sous le diagramme.', ''];

for (const [module, role] of Object.entries(MODULES)) {
  const types = parModule.get(module);
  if (!types) continue;
  const principaux = types.filter(t => !t.dto).sort((a, b) => a.nom.localeCompare(b.nom));
  const dtos = types.filter(t => t.dto).map(t => t.nom).sort();
  md.push(`## \`${module}\` — ${role}`, '', '```mermaid', 'classDiagram');
  const liens = new Set();
  for (const t of principaux) {
    const stereo = t.genre === 'interface' ? '<<interface>>' : t.genre === 'enum' ? '<<enumeration>>'
      : t.genre === 'record' ? '<<record>>' : t.entite ? '<<entity>>' : null;
    md.push(`  class ${t.nom} {`);
    if (stereo) md.push(`    ${stereo}`);
    for (const [type, nom] of t.champs.slice(0, 12)) {
      md.push(`    ${type.replace(/[<>]/g, '~').replace(/[^\w~?\[\], ]/g, '')} ${nom}`);
    }
    if (t.champs.length > 12) md.push(`    … ${t.champs.length - 12} autres`);
    md.push('  }');
    if (t.parent && tousLesTypes.has(t.parent)) liens.add(`  ${t.parent} <|-- ${t.nom}`);
    for (const i of t.interfaces) if (tousLesTypes.has(i)) liens.add(`  ${i} <|.. ${t.nom}`);
    for (const [type] of t.champs) {
      for (const cible of simple(type)) {
        if (cible !== t.nom && tousLesTypes.has(cible) && principaux.some(x => x.nom === cible)) {
          liens.add(`  ${t.nom} --> ${cible}`);
        }
      }
    }
  }
  md.push(...liens, '```', '');
  if (dtos.length) md.push(`DTO : ${dtos.map(d => '`' + d + '`').join(', ')}.`, '');
}

mkdirSync(dirname(SORTIE), { recursive: true });
writeFileSync(SORTIE, md.join('\n') + '\n', 'utf8');
console.log(`Diagrammes écrits : ${SORTIE}`);
