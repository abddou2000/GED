#!/usr/bin/env node
/*
 * Recalcule le tableau de bord de docs/conformite/SUIVI.md à partir de ses
 * lignes détaillées (T-, P-, R-) :
 *   - compteurs par statut courant (et titre « (N lignes) ») ;
 *   - taux de conformité (Identique, puis Identique + réserve UAT, sur le périmètre) ;
 *   - avancement par étape (colonnes Contenu et Vague conservées) ;
 *   - répartition par responsable (colonne « Anomalies qa » conservée : elle
 *     est rédigée à la main par pm, l'outil ne la déduit pas).
 *
 * Les lignes détaillées font foi : pm change le statut d'une ligne, puis lance
 * cet outil au lieu de recompter à la main (source d'erreurs de report).
 *
 * Usage : node outils/suivi/regenerer-tableaux.mjs [--verifier] [chemin/SUIVI.md]
 *   --verifier : n'écrit rien ; échoue (code 1) si le tableau de bord versionné
 *                ne correspond plus aux lignes détaillées.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const racine = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const args = process.argv.slice(2);
const verifier = args.includes('--verifier');
const chemin = args.find((a) => !a.startsWith('--')) ?? join(racine, 'docs/conformite/SUIVI.md');

/* Ordre d'affichage des statuts ; « Hors périmètre » n'entre pas dans le taux. */
const STATUTS = ['À faire', 'En cours', 'Livré', 'Vérifié', 'Proche', 'Identique (réserve UAT)', 'Identique'];
const ENTETES = ['À faire', 'En cours', 'Livré', 'Vérifié', 'Proche', 'Réserve UAT', 'Identique'];
const HORS = 'Hors périmètre';

const origine = readFileSync(chemin, 'utf8');
const fin = origine.includes('\r\n') ? '\r\n' : '\n';
let s = origine.replace(/\r\n/g, '\n');

function echec(message) {
  console.error(`regenerer-tableaux : ${message}`);
  process.exit(2);
}

/* Remplace le bloc compris entre deux repères (le second exclu). */
function remplacerBloc(debut, finBloc, contenu) {
  const a = s.indexOf(debut);
  const b = s.indexOf(finBloc, a);
  if (a < 0 || b < 0) echec(`repères introuvables : « ${debut} » … « ${finBloc} »`);
  const ancien = s.slice(a, b);
  s = s.slice(0, a) + contenu + s.slice(b);
  return ancien;
}

const compter = () => Object.fromEntries([...STATUTS, HORS].map((x) => [x, 0]));

// 1. Lecture des lignes détaillées : N° | Réf. | Exigence | Init. | Étape | Resp. | Statut | Preuve
const total = compter();
const parEtape = new Map();
const parResp = new Map();
let nbLignes = 0;
for (const m of s.matchAll(/^\| ((?:T|P|R)-\d+) \|.*$/gm)) {
  const c = m[0].split(' | ').map((x) => x.trim());
  if (c.length !== 8) echec(`${m[1]} : ${c.length} colonnes au lieu de 8`);
  const [, , , , etapeBrute, resp, statut] = c;
  if (!(statut in total)) echec(`${m[1]} : statut inconnu « ${statut} »`);
  const etape = etapeBrute.split(' ')[0];
  if (!parEtape.has(etape)) parEtape.set(etape, compter());
  if (!parResp.has(resp)) parResp.set(resp, compter());
  parEtape.get(etape)[statut]++;
  parResp.get(resp)[statut]++;
  total[statut]++;
  nbLignes++;
}
if (nbLignes === 0) echec('aucune ligne détaillée trouvée');
const somme = (c) => Object.values(c).reduce((x, y) => x + y, 0);
const pct = (n, d) => `${Math.round((100 * n) / d)} %`;

// 2. Compteurs par statut courant
s = s.replace(/^### Compteurs par statut courant \(\d+ lignes\)$/m,
  `### Compteurs par statut courant (${nbLignes} lignes)`);
remplacerBloc('| Statut courant | Lignes | Part |', '\n\nTaux de conformité', [
  '| Statut courant | Lignes | Part |',
  '|---|---|---|',
  ...[...STATUTS, HORS].map((x) => `| ${x} | ${total[x]} | ${pct(total[x], nbLignes)} |`),
  `| **Total** | **${nbLignes}** | 100 % |`,
].join('\n'));

// 3. Taux de conformité (hors périmètre exclu du dénominateur)
const perimetre = nbLignes - total[HORS];
const identique = total['Identique'];
const avecReserve = identique + total['Identique (réserve UAT)'];
const motifTaux = /(sur les )\d+( lignes dans le périmètre\) : )\*\*\d+ \/ \d+ = \d+ %\*\*( ;\n)\*\*\d+ \/ \d+ = \d+ %\*\*( en comptant les )\d+( lignes)/;
if (!motifTaux.test(s)) echec('phrase du taux de conformité introuvable');
s = s.replace(motifTaux, (_, a, b, c, d, e) =>
  `${a}${perimetre}${b}**${identique} / ${perimetre} = ${pct(identique, perimetre)}**${c}`
  + `**${avecReserve} / ${perimetre} = ${pct(avecReserve, perimetre)}**${d}${total['Identique (réserve UAT)']}${e}`);

// 4. Avancement par étape : Contenu et Vague repris du tableau existant
const cellules = (l) => l.split('|').slice(1, -1).map((x) => x.trim());
const ancienEtapes = s.slice(s.indexOf('| Étape | Contenu | Vague | Lignes |'), s.indexOf('### Répartition par responsable'));
const meta = new Map();
for (const l of ancienEtapes.split('\n')) {
  const c = cellules(l);
  if (c.length > 3 && c[0] && !/^(Étape|-+|\*\*Total)/.test(c[0])) meta.set(c[0], [c[1], c[2]]);
}
for (const e of parEtape.keys()) if (!meta.has(e)) echec(`étape « ${e} » absente du tableau par étape : l'y ajouter (Contenu, Vague)`);
const lignesEtapes = [
  `| Étape | Contenu | Vague | Lignes | ${ENTETES.join(' | ')} |`,
  '|' + '---|'.repeat(4 + ENTETES.length),
];
for (const [e, [contenu, vague]] of meta) {
  const c = parEtape.get(e) ?? compter();
  if (e === '—') {
    lignesEtapes.push(`| — | ${contenu} | — | ${somme(c)} | ${ENTETES.map(() => '—').join(' | ')} |`);
  } else {
    lignesEtapes.push(`| ${e} | ${contenu} | ${vague} | ${somme(c)} | ${STATUTS.map((x) => c[x]).join(' | ')} |`);
  }
}
lignesEtapes.push(`| **Total** | | | **${nbLignes}** | ${STATUTS.map((x) => `**${total[x]}**`).join(' | ')} |`);
remplacerBloc('| Étape | Contenu | Vague | Lignes |', '\n\n### Répartition par responsable', lignesEtapes.join('\n'));

// 5. Répartition par responsable : colonne Anomalies qa conservée
const ancienResp = s.slice(s.indexOf('| Responsable | Lignes |'), s.indexOf('\n\nqa vérifie toutes les lignes'));
const anomalies = new Map();
for (const l of ancienResp.split('\n')) {
  const c = cellules(l);
  if (c.length > 2 && !/^(Responsable|-+)$/.test(c[0])) anomalies.set(c[0], c[c.length - 1]);
}
const ordre = [...anomalies.keys(), ...[...parResp.keys()].filter((r) => !anomalies.has(r))];
remplacerBloc('| Responsable | Lignes |', '\n\nqa vérifie toutes les lignes', [
  `| Responsable | Lignes | ${ENTETES.join(' | ')} | Anomalies qa |`,
  '|' + '---|'.repeat(3 + ENTETES.length),
  ...ordre.filter((r) => parResp.has(r)).map((r) => {
    const c = parResp.get(r);
    return `| ${r} | ${somme(c)} | ${STATUTS.map((x) => c[x]).join(' | ')} | ${anomalies.get(r) ?? '—'} |`;
  }),
].join('\n'));

// 6. Écriture ou vérification
const resultat = s.replace(/\n/g, fin);
const resume = `${nbLignes} lignes ; ` + [...STATUTS, HORS].map((x) => `${x} ${total[x]}`).join(', ')
  + ` ; taux ${identique}/${perimetre} = ${pct(identique, perimetre)} (${avecReserve}/${perimetre} avec réserve UAT)`;
if (verifier) {
  if (resultat !== origine) {
    console.error(`regenerer-tableaux : ${chemin} n'est pas à jour (${resume}). Lancer sans --verifier.`);
    process.exit(1);
  }
  console.log(`À jour : ${resume}`);
} else {
  if (resultat !== origine) writeFileSync(chemin, resultat, 'utf8');
  console.log(`${resultat !== origine ? 'Régénéré' : 'Inchangé'} : ${resume}`);
}
