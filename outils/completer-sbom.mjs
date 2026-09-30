#!/usr/bin/env node
/*
 * Complète le SBOM CycloneDX du back-end avec les composants qui ne passent par
 * aucun gestionnaire de paquets : le moteur Tesseract et les modèles de langue
 * livrés dans backend/tessdata (T-085 : « y compris Tesseract et modèles » ;
 * ANO-E0-002). Appelé par `mvn package` juste après cyclonedx-maven-plugin
 * (backend/pom.xml), sur target/bom.json et target/bom.xml.
 *
 * Chaque composant ajouté porte la propriété ged:origine = hors-gestionnaire,
 * une licence, une version et, pour un modèle, son empreinte SHA-256. Un modèle
 * d'origine non consignée (outils/composants-hors-gestionnaire.mjs) est ajouté
 * avec la version « inconnue » et fait échouer la commande (code 1).
 *
 * Usage : node outils/completer-sbom.mjs <bom.json> [<bom.xml>] [--tessdata DIR]
 */
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { TESSERACT, modelesTesseract } from './composants-hors-gestionnaire.mjs';

export const ORIGINE = 'hors-gestionnaire';
const PREFIXE_REF = 'ged-hors-gestionnaire:';

/** Composants CycloneDX (forme JSON) à ajouter. */
export function composants(tessdata) {
  const { lignes, inconnus } = modelesTesseract(tessdata);
  const liste = [{
    type: 'application', 'bom-ref': PREFIXE_REF + TESSERACT.cle, group: 'tesseract-ocr', name: 'tesseract',
    version: TESSERACT.version, description: `${TESSERACT.usage}. Version : ${TESSERACT.versionDetail}.`,
    licenses: [{ license: { id: TESSERACT.licence } }], purl: `pkg:generic/tesseract-ocr/tesseract@${TESSERACT.version}`,
    externalReferences: [{ type: 'vcs', url: TESSERACT.url }],
    properties: [{ name: 'ged:origine', value: ORIGINE }, { name: 'ged:version', value: TESSERACT.versionDetail }],
  }];
  for (const m of lignes) {
    liste.push({
      type: 'machine-learning-model', 'bom-ref': PREFIXE_REF + m.cle, group: 'tesseract-ocr',
      name: `tessdata-${m.langue}`, version: m.version,
      description: `Modèle Tesseract « ${m.langue} » (${m.versionDetail.replace(/\*/g, '')}), backend/tessdata/${m.fichier}`,
      hashes: [{ alg: 'SHA-256', content: m.sha256 }],
      licenses: [{ license: { id: m.licence } }],
      purl: m.depot ? `pkg:generic/tesseract-ocr/${m.depot}-${m.langue}@${m.version}` : undefined,
      externalReferences: m.url ? [{ type: 'distribution', url: m.url }] : undefined,
      properties: [{ name: 'ged:origine', value: ORIGINE }, { name: 'ged:fichier', value: `backend/tessdata/${m.fichier}` }],
    });
  }
  return { liste: liste.map(c => JSON.parse(JSON.stringify(c))), inconnus };
}

/** Ajoute les composants au SBOM JSON (idempotent), rattachés au composant racine. */
export function completerJson(bom, liste) {
  const racine = bom.metadata?.component?.['bom-ref'];
  bom.components = (bom.components || []).filter(c => !String(c['bom-ref'] || '').startsWith(PREFIXE_REF));
  bom.components.push(...liste);
  bom.dependencies = (bom.dependencies || []).filter(d => !String(d.ref).startsWith(PREFIXE_REF));
  const refs = liste.map(c => c['bom-ref']);
  if (racine) {
    let d = bom.dependencies.find(x => x.ref === racine);
    if (!d) { d = { ref: racine, dependsOn: [] }; bom.dependencies.push(d); }
    d.dependsOn = [...(d.dependsOn || []).filter(r => !r.startsWith(PREFIXE_REF)), ...refs];
  }
  for (const r of refs) bom.dependencies.push({ ref: r, dependsOn: [] });
  return bom;
}

const x = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

function composantXml(c) {
  const l = [`<component type="${x(c.type)}" bom-ref="${x(c['bom-ref'])}">`,
    `<group>${x(c.group)}</group>`, `<name>${x(c.name)}</name>`, `<version>${x(c.version)}</version>`,
    `<description>${x(c.description)}</description>`];
  if (c.hashes) l.push('<hashes>' + c.hashes.map(h => `<hash alg="${x(h.alg)}">${x(h.content)}</hash>`).join('') + '</hashes>');
  l.push('<licenses>' + c.licenses.map(li => `<license><id>${x(li.license.id)}</id></license>`).join('') + '</licenses>');
  if (c.purl) l.push(`<purl>${x(c.purl)}</purl>`);
  if (c.externalReferences) {
    l.push('<externalReferences>' + c.externalReferences
      .map(r => `<reference type="${x(r.type)}"><url>${x(r.url)}</url></reference>`).join('') + '</externalReferences>');
  }
  l.push('<properties>' + c.properties.map(p => `<property name="${x(p.name)}">${x(p.value)}</property>`).join('') + '</properties>');
  l.push('</component>');
  return l.join('');
}

/** Ajoute les composants au SBOM XML (texte), sans rien toucher d'autre. */
export function completerXml(texte, liste, racine) {
  if (texte.includes(`bom-ref="${PREFIXE_REF}`)) return texte;           // déjà complété
  const debutDeps = texte.indexOf('<dependencies>');
  const fin = texte.lastIndexOf('</components>', debutDeps < 0 ? texte.length : debutDeps);
  if (fin < 0) throw new Error('SBOM XML sans élément <components>');
  let t = texte.slice(0, fin) + liste.map(composantXml).join('\n') + '\n' + texte.slice(fin);
  if (racine && t.includes('<dependencies>')) {
    const refs = liste.map(c => `<dependency ref="${x(c['bom-ref'])}"/>`).join('');
    const ouvert = `<dependency ref="${x(racine)}">`, ferme = `<dependency ref="${x(racine)}"/>`;
    if (t.includes(ouvert)) t = t.replace(ouvert, ouvert + refs);
    else if (t.includes(ferme)) t = t.replace(ferme, ouvert + refs + '</dependency>');
    t = t.replace('</dependencies>', refs + '</dependencies>');
  }
  return t;
}

// --- Ligne de commande -------------------------------------------------------
if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const args = process.argv.slice(2);
  const i = args.indexOf('--tessdata');
  const tessdata = i >= 0 ? args.splice(i, 2)[1]
    : join(dirname(fileURLToPath(import.meta.url)), '..', 'backend', 'tessdata');
  const [json, xml] = args;
  if (!json || !existsSync(json)) {
    console.error(`SBOM JSON absent : ${json || '(non précisé)'}`);
    process.exit(2);
  }
  const { liste, inconnus } = composants(tessdata);
  const bom = completerJson(JSON.parse(readFileSync(json, 'utf-8')), liste);
  writeFileSync(json, JSON.stringify(bom, null, 2) + '\n', 'utf-8');
  if (xml && existsSync(xml)) {
    writeFileSync(xml, completerXml(readFileSync(xml, 'utf-8'), liste, bom.metadata?.component?.['bom-ref']), 'utf-8');
  }
  console.log(`SBOM complété : ${liste.length} composant(s) hors gestionnaire (${liste.map(c => c.name).join(', ')})`);
  if (inconnus.length) {
    console.error(`Modèles Tesseract d'origine non consignée : ${inconnus.join(' ; ')}`);
    process.exit(1);
  }
}
