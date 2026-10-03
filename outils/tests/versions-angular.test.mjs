/*
 * Versions d'Angular figées dans frontend/package-lock.json (T-070, ANO-E0-004).
 * Lancement : node --test outils/tests/*.test.mjs
 *
 * `npm audit` (job front de la CI) a besoin du registre npm ; ce test, lui, tourne
 * hors ligne sur le seul fichier de verrouillage : il refuse un retour à une version
 * d'Angular visée par un avis déjà corrigé, et une montée partielle (paquets
 * @angular/* désaccordés, que npm ne sait plus résoudre ensemble).
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const FRONT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'frontend');
const lock = JSON.parse(readFileSync(join(FRONT, 'package-lock.json'), 'utf-8'));
const pkg = JSON.parse(readFileSync(join(FRONT, 'package.json'), 'utf-8'));

/** Version installée de chaque paquet @angular/* (premier niveau de node_modules). */
const angular = Object.fromEntries(
  Object.entries(lock.packages)
    .filter(([chemin]) => /^node_modules\/@angular\/[^/]+$/.test(chemin))
    .map(([chemin, p]) => [chemin.slice('node_modules/'.length), p.version]),
);

/** Comparaison de versions x.y.z (les préversions comptent comme antérieures). */
function comparer(a, b) {
  const [na, pa] = a.split('-'), [nb, pb] = b.split('-');
  const da = na.split('.').map(Number), db = nb.split('.').map(Number);
  for (let i = 0; i < 3; i++) if (da[i] !== db[i]) return da[i] - db[i];
  if (pa && !pb) return -1;
  if (!pa && pb) return 1;
  return 0;
}

/* Avis corrigés : version minimale admise par paquet. Ajouter une ligne à chaque
   avis traité, pour qu'une régression du verrouillage échoue ici avant la CI. */
const AVIS = [
  { avis: 'GHSA-ff3f-86qr-9cv3', paquets: ['@angular/router'], corrige: '22.2.0' },
  { avis: 'GHSA-p297-fm68-3q8c', paquets: ['@angular/common', '@angular/forms', '@angular/platform-browser'], corrige: '22.1.1' },
  { avis: 'GHSA-hh8m-fm6v-7cvg', paquets: ['@angular/core', '@angular/compiler', '@angular/animations'], corrige: '22.1.0' },
];

/* Paquets publiés ensemble, à la même version. */
const FAMILLES = {
  framework: ['animations', 'common', 'compiler', 'compiler-cli', 'core', 'forms', 'platform-browser', 'router'],
  outillage: ['build', 'cli'],
  composants: ['cdk', 'material'],
};

test('Angular : aucun paquet livré sous la version qui corrige un avis connu (ANO-E0-004)', () => {
  for (const { avis, paquets, corrige } of AVIS) {
    for (const p of paquets) {
      assert.ok(angular[p], `${p} absent de package-lock.json`);
      assert.ok(comparer(angular[p], corrige) >= 0,
        `${p} ${angular[p]} est visé par ${avis} (corrigé en ${corrige})`);
    }
  }
});

test('Angular : chaque famille de paquets @angular/* est à une seule version', () => {
  for (const [famille, noms] of Object.entries(FAMILLES)) {
    const versions = new Set(noms.map(n => angular[`@angular/${n}`]));
    assert.equal(versions.size, 1,
      `famille ${famille} désaccordée : ${noms.map(n => `${n} ${angular[`@angular/${n}`]}`).join(', ')}`);
  }
  /* Les familles ont leur propre numéro de correctif mais suivent la même mineure. */
  const mineure = v => v.split('.').slice(0, 2).join('.');
  for (const n of ['cdk', 'cli']) {
    assert.equal(mineure(angular[`@angular/${n}`]), mineure(angular['@angular/core']),
      `@angular/${n} ${angular[`@angular/${n}`]} et @angular/core ${angular['@angular/core']} sur des mineures différentes`);
  }
});

test('Angular : package.json ne permet pas de résoudre une version visée par un avis', () => {
  const demandes = { ...pkg.dependencies, ...pkg.devDependencies };
  for (const { avis, paquets, corrige } of AVIS) {
    for (const p of paquets) {
      if (!demandes[p]) continue; // dépendance transitive
      const plancher = demandes[p].replace(/^[\^~]/, '');
      assert.ok(comparer(plancher, corrige) >= 0, `${p} : package.json demande ${demandes[p]}, ${avis} corrigé en ${corrige}`);
    }
  }
});
