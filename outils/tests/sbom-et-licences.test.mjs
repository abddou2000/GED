/*
 * Tests du SBOM complété (T-085, ANO-E0-002) et du contrôle des licences
 * (P-19, ANO-E0-003). Lancement : node --test outils/tests/*.test.mjs
 *
 * Le contrôle des licences est rejoué comme en CI (`--verifier`) sur une copie
 * de l'outil, avec des SBOM minimaux : aucun fichier du dépôt n'est modifié.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, copyFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { composants, completerJson, completerXml } from '../completer-sbom.mjs';

const OUTILS = join(dirname(fileURLToPath(import.meta.url)), '..');
const TESSDATA = join(OUTILS, '..', 'backend', 'tessdata');

const racine = { 'bom-ref': 'pkg:maven/com.ipt/ged@1', type: 'application', name: 'ged' };
const bomMaven = (...comps) => ({
  bomFormat: 'CycloneDX', specVersion: '1.6', metadata: { component: racine },
  components: comps, dependencies: [{ ref: racine['bom-ref'], dependsOn: comps.map(c => c['bom-ref']) }],
});
const comp = (group, name, ...licences) => ({
  type: 'library', 'bom-ref': `pkg:maven/${group}/${name}@1.0`, group, name, version: '1.0',
  licenses: licences.map(id => ({ license: { id } })),
});

/** Code de sortie de `registre-dependances.mjs --verifier` sur une copie, pour ces SBOM. */
function verifier(back, front = bomMaven()) {
  const r = mkdtempSync(join(tmpdir(), 'ged-registre-'));
  try {
    mkdirSync(join(r, 'outils')); mkdirSync(join(r, 'backend/target'), { recursive: true });
    mkdirSync(join(r, 'frontend/dist'), { recursive: true }); mkdirSync(join(r, 'docs'));
    for (const f of ['registre-dependances.mjs', 'composants-hors-gestionnaire.mjs']) copyFileSync(join(OUTILS, f), join(r, 'outils', f));
    writeFileSync(join(r, 'backend/target/bom.json'), JSON.stringify(back));
    writeFileSync(join(r, 'frontend/dist/bom.json'), JSON.stringify(front));
    const p = spawnSync(process.execPath, [join(r, 'outils/registre-dependances.mjs'), '--verifier'], { encoding: 'utf-8' });
    return { code: p.status, sortie: p.stdout + p.stderr };
  } finally {
    rmSync(r, { recursive: true, force: true });
  }
}

test('SBOM : Tesseract et les quatre modèles ajoutés, avec version, licence et empreinte', () => {
  const { liste, inconnus } = composants(TESSDATA);
  assert.deepEqual(inconnus, []);
  const bom = completerJson(bomMaven(comp('org.x', 'y', 'MIT')), liste);
  const t = bom.components.filter(c => /tesseract|tessdata/i.test(`${c.group} ${c.name}`));
  assert.deepEqual(t.map(c => c.name).sort(), ['tessdata-ara', 'tessdata-eng', 'tessdata-fra', 'tessdata-osd', 'tesseract']);
  for (const c of t) {
    assert.ok(c.version, `${c.name} sans version`);
    assert.equal(c.licenses[0].license.id, 'Apache-2.0');
    assert.ok(c.properties.some(p => p.name === 'ged:origine' && p.value === 'hors-gestionnaire'));
  }
  for (const m of t.filter(c => c.name.startsWith('tessdata'))) {
    assert.equal(m.version, '4.1.0');
    assert.match(m.hashes[0].content, /^[0-9a-f]{64}$/);
  }
  // Rattachés au composant racine ; une seconde passe n'ajoute rien.
  const deps = bom.dependencies.find(d => d.ref === racine['bom-ref']).dependsOn;
  assert.equal(deps.filter(r => r.startsWith('ged-hors-gestionnaire:')).length, 5);
  const encore = completerJson(bom, liste);
  assert.equal(encore.components.length, 6);
});

test('SBOM XML : composants et dépendances insérés à leur place', () => {
  const { liste } = composants(TESSDATA);
  const xml = `<?xml version="1.0"?><bom xmlns="http://cyclonedx.org/schema/bom/1.6"><metadata><component type="application" bom-ref="${racine['bom-ref']}"><name>ged</name></component></metadata>`
    + '<components><component type="library" bom-ref="a"><name>a</name></component></components>'
    + `<dependencies><dependency ref="${racine['bom-ref']}"><dependency ref="a"/></dependency><dependency ref="a"/></dependencies></bom>`;
  const t = completerXml(xml, liste, racine['bom-ref']);
  assert.equal((t.match(/<component type="machine-learning-model"/g) || []).length, 4);
  assert.ok(t.indexOf('tessdata-osd') < t.indexOf('</components>'));
  assert.ok(t.includes(`<dependency ref="${racine['bom-ref']}"><dependency ref="ged-hors-gestionnaire:tesseract-ocr"/>`));
  assert.equal(completerXml(t, liste, racine['bom-ref']), t);
});

test('Licences : un SBOM permissif et veraPDF en double licence passent', () => {
  const r = verifier(bomMaven(comp('org.x', 'y', 'MIT'),
    comp('org.verapdf', 'parser', 'GPL-3.0-only', 'MPL-2.0'),
    comp('org.verapdf', 'verapdf-xmp-core-jakarta', 'BSD-3-Clause')));
  assert.equal(r.code, 0, r.sortie);
});

test('Licences : mysql-connector-j est refusé malgré son arbitrage (ANO-E0-003 a)', () => {
  const r = verifier(bomMaven(comp('com.mysql', 'mysql-connector-j', 'GPL-2.0-with-universal-foss-exception')));
  assert.equal(r.code, 1, r.sortie);
  assert.match(r.sortie, /refusés.*mysql-connector-j/);
});

test('Licences : un composant GPL-3.0-only seul dans le groupe org.verapdf est refusé (ANO-E0-003 b)', () => {
  const r = verifier(bomMaven(comp('org.verapdf', 'composant-gpl-seul', 'GPL-3.0-only')));
  assert.equal(r.code, 1, r.sortie);
  assert.match(r.sortie, /sans arbitrage.*composant-gpl-seul/);
});

test('Licences : GPL et AGPL non arbitrées refusées ; composants hors gestionnaire du SBOM ignorés', () => {
  assert.equal(verifier(bomMaven(comp('org.x', 'gpl', 'GPL-3.0-only'))).code, 1);
  assert.equal(verifier(bomMaven(), bomMaven(comp('', 'agpl', 'AGPL-3.0-only'))).code, 1);
  const { liste } = composants(TESSDATA);
  assert.equal(verifier(completerJson(bomMaven(comp('org.x', 'y', 'MIT')), liste)).code, 0);
});
