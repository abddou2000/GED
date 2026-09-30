#!/usr/bin/env node
/*
 * Génère docs/DEPENDANCES.md, registre lisible des dépendances (DAT 8.3),
 * à partir des deux SBOM CycloneDX produits par la construction :
 *   - backend/target/bom.json   (mvn package, cyclonedx-maven-plugin)
 *   - frontend/dist/bom.json    (npm run sbom, @cyclonedx/cyclonedx-npm)
 *
 * Les SBOM font foi ; ce registre en est la vue humaine, avec l'usage de chaque
 * dépendance directe et le contrôle de compatibilité des licences avec la
 * cession de propriété à MMED (DAT 11.2, Article 45).
 *
 * Usage : node outils/registre-dependances.mjs [--verifier]
 *   --verifier : n'écrit rien ; avertit si le registre versionné n'est plus à
 *                jour, échoue si une licence non permissive n'a pas été arbitrée.
 */
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { TESSERACT, modelesTesseract, HORS_GESTIONNAIRE_FRONT } from './composants-hors-gestionnaire.mjs';

const racine = join(dirname(fileURLToPath(import.meta.url)), '..');
const SBOM_BACK = join(racine, 'backend/target/bom.json');
const SBOM_FRONT = join(racine, 'frontend/dist/bom.json');
const SORTIE = join(racine, 'docs/DEPENDANCES.md');
const TESSDATA = join(racine, 'backend/tessdata');

/* Usage des dépendances DIRECTES. Une dépendance directe absente de cette
   table apparaît « usage à documenter » : c'est voulu, l'ajout d'une
   bibliothèque doit s'accompagner de sa justification (Article 50). */
const USAGES = {
  // Back-end
  'org.springframework.boot:spring-boot-starter-web': 'API REST, serveur Tomcat embarqué',
  'org.springframework.boot:spring-boot-starter-data-jpa': 'Persistance JPA / Hibernate',
  'org.springframework.boot:spring-boot-starter-validation': 'Bean Validation des DTO (DAT 6.3)',
  'org.springframework.boot:spring-boot-starter-security': 'Chaîne de sécurité, authentification',
  'org.springframework.boot:spring-boot-starter-actuator': 'Sondes de santé et métriques (DAT 6.7)',
  'org.springframework.boot:spring-boot-starter-liquibase': 'Migrations de schéma (DAT 4.2)',
  'io.micrometer:micrometer-registry-prometheus': 'Export des métriques au format Prometheus (DAT 6.7)',
  'org.apache.poi:poi-ooxml': 'Lecture des formats Office (OCR, indexation)',
  'org.apache.poi:poi-scratchpad': 'Lecture des anciens formats Office',
  'org.apache.pdfbox:pdfbox': 'Couche texte et rendu des PDF (OCR, DAT 4.3.4)',
  'io.jsonwebtoken:jjwt-api': 'Jetons JWT (API)',
  'io.jsonwebtoken:jjwt-impl': 'Jetons JWT (implémentation)',
  'io.jsonwebtoken:jjwt-jackson': 'Jetons JWT (sérialisation JSON)',
  'org.projectlombok:lombok': 'Génération de code à la compilation (absent du JAR livré)',
  'com.h2database:h2': 'Base de développement embarquée (hors production)',
  'com.mysql:mysql-connector-j': 'Pilote MySQL (socle historique, remplacé par PostgreSQL en E1)',
  'org.postgresql:postgresql': 'Pilote PostgreSQL (DAT 2.2)',
  'org.flywaydb:flyway-core': 'Migrations de schéma (socle historique, remplacé par Liquibase en E1)',
  'org.flywaydb:flyway-mysql': 'Migrations de schéma MySQL (socle historique)',
  'org.liquibase:liquibase-core': 'Migrations de schéma (DAT 4.2)',
  'org.springdoc:springdoc-openapi-starter-webmvc-ui': 'Spécification OpenAPI 3 et Swagger UI (DAT 5.3)',
  'org.springframework.boot:spring-boot-starter-mail': 'Canal e-mail des notifications, relais SMTP de MMED (DAT 12.9)',
  'org.springframework.security:spring-security-ldap': 'Authentification par l\'annuaire (search-then-bind, DAT 3.2)',
  'com.unboundid:unboundid-ldapsdk': 'Annuaire simulé des profils dev et test (jamais en production)',
  'org.apache.tika:tika-core': 'Détection du type réel des fichiers par signature (DAT 6.1.5)',
  'org.apache.pdfbox:xmpbox': 'Métadonnées XMP d\'identification PDF/A des copies de conservation (DAT 6.1.4)',
  'org.verapdf:validation-model-jakarta': 'Validation PDF/A-2 des copies de conservation (veraPDF, DAT 6.1.4, 12.6)',
  // Front-end
  '@angular:animations': 'Animations Angular',
  '@angular:cdk': 'Composants de base Angular (CDK)',
  '@angular:common': 'Socle Angular',
  '@angular:compiler': 'Socle Angular',
  '@angular:core': 'Socle Angular',
  '@angular:forms': 'Formulaires réactifs et validation (DAT 6.3)',
  '@angular:material': 'Composants graphiques Material',
  '@angular:platform-browser': 'Socle Angular (navigateur)',
  '@angular:router': 'Routage de l\'application',
  '@fontsource-variable:fraunces': 'Police de titres, servie localement',
  '@fontsource-variable:inter': 'Police de texte, servie localement',
  '@fontsource-variable:nunito': 'Police de texte, servie localement',
  ':primeicons': 'Jeu d\'icônes',
  ':rxjs': 'Programmation réactive (socle Angular)',
  ':tslib': 'Assistants d\'exécution TypeScript',
};

/* Composants tiers qui ne passent par aucun gestionnaire de paquets (moteur
   OCR, modèles de langue, icônes recopiées) : liste et empreintes des modèles
   dans outils/composants-hors-gestionnaire.mjs. Tesseract et ses modèles
   figurent aussi dans le SBOM du back-end (outils/completer-sbom.mjs, appelé
   par `mvn package`) ; ils n'apparaissent ici que dans leur propre tableau. */
const MODELES = modelesTesseract(TESSDATA);
const HORS_GESTIONNAIRE = [
  { ...TESSERACT, version: TESSERACT.versionDetail },
  ...MODELES.lignes.map(m => ({ ...m, version: m.versionDetail })),
  ...HORS_GESTIONNAIRE_FRONT,
];

/* Licences dont la déclaration du paquet est illisible par l'outil mais
   vérifiée à la main dans le fichier LICENSE du paquet. */
const LICENCES_VERIFIEES = {
  ':primeicons': 'MIT',
};

/* Double licence dont l'option est retenue pour tout un groupe (préfixe
   « groupe: »). L'option n'est retenue que pour un composant qui la DÉCLARE
   parmi ses licences : un composant du groupe publié sous une autre licence
   seule (GPL-3.0-only, BSD…) est évalué sur sa propre déclaration (ANO-E0-003). */
const LICENCES_RETENUES_GROUPES = {
  // veraPDF : GPL-3.0-or-later OU MPL-2.0-or-later ; MPL-2.0 retenue (lot E7, dev3).
  'org.verapdf:': 'MPL-2.0',
};

/* Composants REFUSÉS quel que soit leur arbitrage : leur présence dans un SBOM
   fait échouer --verifier. Un arbitrage qui conclut « ne pas livrer » est un
   refus, pas une acceptation (ANO-E0-003). */
const REFUSES = {
  'com.mysql:mysql-connector-j': 'GPL-2.0 avec exception FOSS universelle : l\'exception couvre l\'usage avec un logiciel sous licence libre, **pas une application propriétaire cédée à MMED**. Pilote du socle historique, remplacé par PostgreSQL (E1) : ne doit plus figurer dans le livrable.',
};

// --- Classement des licences (DAT 11.2) ------------------------------------
const PERMISSIVES = new Set(['Apache-2.0', 'MIT', 'BSD-2-Clause', 'BSD-3-Clause', 'ISC', '0BSD',
  'OFL-1.1', 'CC0-1.0', 'Unicode-3.0', 'Unicode-DFS-2016', 'EDL-1.0', 'Bouncy-Castle', 'MIT-0',
  'BlueOak-1.0.0', 'Python-2.0', 'Zlib', 'PostgreSQL']);
const COPYLEFT_FAIBLE = /^(LGPL|EPL|MPL|CDDL|GPL-2\.0-with-classpath-exception)/;

/** Ramène un libellé libre de licence à un identifiant SPDX quand il est reconnaissable. */
function normaliser(libelle) {
  if (!libelle) return null;
  const l = libelle.trim();
  if (/^EPL 1\.0$/i.test(l)) return 'EPL-1.0';
  if (/^GNU Lesser General Public License$/i.test(l)) return 'LGPL-2.1';
  if (/Universal FOSS Exception/i.test(l)) return 'GPL-2.0-with-universal-foss-exception';
  return l;
}

function classer(id) {
  if (PERMISSIVES.has(id)) return 'permissive';
  if (COPYLEFT_FAIBLE.test(id)) return 'copyleft-faible';
  return 'a-examiner';
}

const RANG = { 'permissive': 0, 'copyleft-faible': 1, 'a-examiner': 2 };
const LIBELLE_CLASSE = {
  'permissive': 'Compatible',
  'copyleft-faible': 'Compatible sous condition',
  'a-examiner': '**À examiner**',
};

/* Plusieurs licences déclarées par un même composant Maven ou npm expriment un
   choix (double licence : logback, H2, API Jakarta). On retient la plus
   favorable, et on l'indique. */
function licencesDe(composant, cle) {
  if (LICENCES_VERIFIEES[cle]) return { ids: [LICENCES_VERIFIEES[cle]], verifiee: true };
  const ids = (composant.licenses || []).map(l =>
    l.expression ? l.expression : normaliser(l.license?.id || l.license?.name)).filter(Boolean);
  const groupe = Object.keys(LICENCES_RETENUES_GROUPES).find(p => cle.startsWith(p));
  if (groupe) {
    const retenue = LICENCES_RETENUES_GROUPES[groupe];
    // Déclarée seule, dans une liste d'options ou dans une expression « … OR … ».
    const declaree = ids.some(id => id === retenue || id.startsWith(retenue + '-')
      || id.split(/\s+OR\s+/i).some(o => o.replace(/[()]/g, '').trim().startsWith(retenue)));
    if (declaree) return { ids: [retenue], verifiee: true, choixGroupe: true };
  }
  return { ids, verifiee: false };
}

function evaluer(ids) {
  if (ids.length === 0) return 'a-examiner';
  return ids.map(classer).sort((a, b) => RANG[a] - RANG[b])[0];
}

// --- Lecture des SBOM ---------------------------------------------------------
function lire(chemin, ecosysteme) {
  if (!existsSync(chemin)) {
    console.error(`SBOM absent : ${chemin}. Lancer d'abord `
      + (ecosysteme === 'maven' ? '`mvn package` dans backend/.' : '`npm run sbom` dans frontend/.'));
    process.exit(2);
  }
  const bom = JSON.parse(readFileSync(chemin, 'utf-8'));
  const racineRef = bom.metadata.component['bom-ref'];
  const graphe = new Map((bom.dependencies || []).map(d => [d.ref, d.dependsOn || []]));
  const directs = new Set(graphe.get(racineRef) || []);

  // Pour une transitive, on remonte à la première dépendance directe qui l'amène.
  const parent = new Map();
  for (const d of directs) {
    const pile = [d];
    while (pile.length) {
      const courant = pile.pop();
      for (const enfant of graphe.get(courant) || []) {
        if (!directs.has(enfant) && !parent.has(enfant)) {
          parent.set(enfant, d);
          pile.push(enfant);
        }
      }
    }
  }
  const parRef = new Map((bom.components || []).map(c => [c['bom-ref'], c]));
  const nomDe = c => (c.group ? `${c.group}:` : (ecosysteme === 'npm' ? ':' : '')) + c.name;

  const horsGestionnaire = c => (c.properties || []).some(p => p.name === 'ged:origine' && p.value === 'hors-gestionnaire');
  return (bom.components || []).filter(c => !horsGestionnaire(c)).map(c => {
    const cle = nomDe(c);
    const { ids, verifiee, choixGroupe } = licencesDe(c, cle);
    const direct = directs.has(c['bom-ref']);
    const amenePar = parent.get(c['bom-ref']);
    return {
      cle,
      nom: ecosysteme === 'npm' ? (c.group ? `${c.group}/${c.name}` : c.name) : `${c.group}:${c.name}`,
      version: c.version || '',
      licences: ids,
      verifiee,
      choixGroupe: Boolean(choixGroupe),
      classe: evaluer(ids),
      direct,
      usage: direct
        ? (USAGES[cle] || '_usage à documenter_')
        : (amenePar && parRef.get(amenePar) ? `transitive de ${nomDe(parRef.get(amenePar)).replace(/^:/, '')}` : 'transitive'),
    };
  }).sort((a, b) => (b.direct - a.direct) || a.nom.localeCompare(b.nom));
}

// --- Rendu --------------------------------------------------------------------
const echapper = s => String(s).replace(/\|/g, '\\|');
function tableau(lignes) {
  const t = ['| Nom | Version | Licence | Compatibilité | Usage |', '|---|---|---|---|---|'];
  for (const l of lignes) {
    const lic = (l.licences.join(' ou ') || 'non déclarée') + (l.verifiee ? ' (vérifiée à la main)' : '');
    const classe = REFUSES[l.cle] ? '**Refusé**'
      : l.classe === 'a-examiner' && arbitrage(l) ? 'Compatible (arbitrage ci-dessus)' : LIBELLE_CLASSE[l.classe];
    t.push(`| ${echapper(l.nom)} | ${echapper(l.version)} | ${echapper(lic)} | ${classe} | ${echapper(l.usage)} |`);
  }
  return t.join('\n');
}

/* Arbitrages écrits pour chaque composant « à examiner » ou sous condition :
   un registre qui liste un risque sans le trancher ne sert à rien. */
const ARBITRAGES = {
  'org.hibernate.orm:hibernate-core': 'LGPL-2.1 : utilisation comme bibliothèque non modifiée, liée dynamiquement (JAR séparé dans le JAR Spring Boot) — aucune obligation sur le code de la GED. Ne jamais modifier ni recompiler Hibernate dans le livrable.',
  'ch.qos.logback:logback-classic': 'Double licence EPL-1.0 ou LGPL-2.1, au choix : EPL-1.0 retenue, bibliothèque non modifiée.',
  'ch.qos.logback:logback-core': 'Idem logback-classic.',
  'com.h2database:h2': 'Double licence MPL-2.0 ou EPL-1.0 : copyleft au niveau du fichier, non modifié. Base de développement, sans usage en production.',
  'jakarta.annotation:jakarta.annotation-api': 'EPL-2.0 ou GPL-2.0 avec exception Classpath : EPL-2.0 retenue, API non modifiée.',
  'jakarta.transaction:jakarta.transaction-api': 'Idem jakarta.annotation-api.',
  'org.aspectj:aspectjweaver': 'EPL-2.0 : copyleft au niveau du fichier, bibliothèque non modifiée (tirée par spring-boot-starter-data-jpa).',
  'org.verapdf:': 'veraPDF (validation PDF/A des copies de conservation) : double licence GPL-3.0-or-later ou MPL-2.0-or-later ; **MPL-2.0 retenue**, copyleft au niveau du fichier, bibliothèque non modifiée, livrée dans son JAR d\'origine. Obligation : fournir le source des fichiers MPL s\'ils étaient modifiés (ils ne le sont pas). Aucun code GPL n\'est retenu.',
  'net.sf.saxon:Saxon-HE': 'MPL-2.0 (édition Home, libre), tirée par veraPDF pour ses règles de validation XSLT : copyleft au niveau du fichier, bibliothèque non modifiée. Les éditions PE/EE (commerciales) ne sont pas utilisées.',
  'javax.xml.bind:jaxb-api': 'Double licence CDDL-1.1 ou GPL-2.0 avec exception Classpath : CDDL-1.1 retenue, API non modifiée (tirée par liquibase-core).',
  'net.java.dev.stax-utils:stax-utils': 'BSD-4-Clause (tirée par veraPDF) : licence permissive, compatible avec la cession ; la clause de publicité impose de citer le détenteur dans toute publicité qui mentionnerait cette fonctionnalité — aucune n\'est prévue. Bibliothèque non modifiée.',
  'org.mozilla:rhino': 'MPL-2.0, moteur JavaScript tiré par veraPDF (évaluation de règles) : copyleft au niveau du fichier, bibliothèque non modifiée. Aucun script de l\'application n\'y est exécuté.',
};

/** Arbitrage d'un composant : clé exacte, sinon celui de son groupe (clé
    « groupe: »), qui ne couvre que les composants dont la licence a été
    retenue par le choix du groupe ou n'est pas à examiner : un composant GPL
    seul dans un groupe arbitré reste « à examiner ». Jamais pour un refusé. */
function arbitrage(l) {
  if (REFUSES[l.cle]) return null;
  if (ARBITRAGES[l.cle]) return ARBITRAGES[l.cle];
  const groupe = Object.keys(ARBITRAGES).find(p => p.endsWith(':') && l.cle.startsWith(p));
  return groupe && (l.choixGroupe || l.classe !== 'a-examiner') ? ARBITRAGES[groupe] : null;
}

function generer() {
  const back = lire(SBOM_BACK, 'maven');
  const front = lire(SBOM_FRONT, 'npm');
  const tous = [...back, ...front];
  const compte = c => tous.filter(l => l.classe === c).length;
  const sensibles = tous.filter(l => l.classe !== 'permissive');

  const md = [];
  md.push('# Registre des dépendances — GED Marchica Med', '');
  md.push('> **Fichier généré** par `node outils/registre-dependances.mjs` à partir des SBOM CycloneDX');
  md.push('> (`backend/target/bom.json`, `frontend/dist/bom.json`). Ne pas modifier à la main : corriger');
  md.push('> la table des usages ou des arbitrages dans le script, puis régénérer. La CI signale un registre en retard.', '');
  md.push('Exigences couvertes : DAT 8.3 (traçabilité complète des dépendances, version et licence),');
  md.push('DAT 11.2 / Article 45 (licence compatible avec la cession de propriété à MMED), DAT 6.2.3 A06.', '');
  md.push('## Synthèse', '');
  md.push('| | Nombre |', '|---|---|');
  md.push(`| Dépendances back-end (Maven, portée d'exécution) | ${back.length} |`);
  md.push(`| Dépendances front-end livrées (npm, hors outillage de développement) | ${front.length} |`);
  md.push(`| Composants hors gestionnaire de paquets | ${HORS_GESTIONNAIRE.length} |`);
  md.push(`| Licence permissive — compatible | ${compte('permissive') + HORS_GESTIONNAIRE.length} |`);
  md.push(`| Copyleft faible — compatible sous condition de non-modification | ${compte('copyleft-faible')} |`);
  const arbitres = tous.filter(l => l.classe === 'a-examiner' && arbitrage(l)).length;
  const refuses = tous.filter(l => REFUSES[l.cle]);
  md.push(`| Hors des classes ci-dessus, compatible par arbitrage écrit | ${arbitres} |`);
  md.push(`| **À examiner** (sans arbitrage) | ${compte('a-examiner') - arbitres - refuses.filter(l => l.classe === 'a-examiner').length} |`);
  md.push(`| **Refusés** (interdits dans le livrable) | ${refuses.length} |`, '');
  md.push('## Règle de compatibilité appliquée (DAT 11.2)', '');
  md.push('La cession à MMED porte sur le code développé pour le marché ; les bibliothèques tierces restent');
  md.push('sous leur licence. Une licence est compatible si elle permet à MMED d\'utiliser, modifier et');
  md.push('redistribuer la GED sans obligation de publier son code ni redevance :', '');
  md.push('- **Permissive** (Apache-2.0, MIT, BSD, ISC, OFL-1.1 pour les polices, CC0) : compatible.');
  md.push('- **Copyleft faible** (LGPL, EPL, MPL, CDDL, GPL avec exception Classpath) : compatible tant que la');
  md.push('  bibliothèque est utilisée **telle quelle** ; toute modification de la bibliothèque elle-même');
  md.push('  devrait être publiée. Règle d\'équipe : ne jamais modifier une bibliothèque tierce.');
  md.push('- **Copyleft fort** (GPL, AGPL) ou licence absente : **à examiner**, bloquant pour la mise en production.', '');
  md.push('## Points d\'attention et arbitrages', '');
  for (const l of sensibles) {
    const arb = REFUSES[l.cle] ? `**REFUSÉ** : ${REFUSES[l.cle]}` : arbitrage(l) || '_Arbitrage à rédiger._';
    md.push(`- **${l.nom} ${l.version}** (${l.licences.join(' ou ') || 'licence non déclarée'}) — ${arb}`);
  }
  md.push('');
  md.push('## Composants hors gestionnaire de paquets', '');
  md.push('Tesseract et ses modèles figurent aussi dans le SBOM CycloneDX du back-end (propriété');
  md.push('`ged:origine` = `hors-gestionnaire`, empreinte SHA-256 de chaque modèle), ajoutés à chaque');
  md.push('`mvn package` par `outils/completer-sbom.mjs`.', '');
  md.push('| Nom | Version | Licence | Usage |', '|---|---|---|---|');
  for (const h of HORS_GESTIONNAIRE) md.push(`| ${h.nom} | ${h.version} | ${h.licence} | ${h.usage} |`);
  md.push('');
  md.push('## Back-end — dépendances directes', '', tableau(back.filter(l => l.direct)), '');
  md.push('## Front-end — dépendances directes livrées', '', tableau(front.filter(l => l.direct)), '');
  md.push('## Back-end — dépendances transitives', '', tableau(back.filter(l => !l.direct)), '');
  md.push('## Front-end — dépendances transitives livrées', '', tableau(front.filter(l => !l.direct)), '');
  return { texte: md.join('\n'), nonArbitres: sensibles.filter(l => !REFUSES[l.cle] && !arbitrage(l)), refuses };
}

const { texte, nonArbitres, refuses } = generer();
if (process.argv.includes('--verifier')) {
  const actuel = existsSync(SORTIE) ? readFileSync(SORTIE, 'utf-8').replace(/\r\n/g, '\n') : '';
  /* Un registre en retard est signalé sans bloquer : le SBOM, produit à
     chaque construction, reste la source de vérité. Une licence non arbitrée,
     elle, bloque : c'est un risque juridique pour la cession à MMED. */
  if (actuel !== texte) {
    console.warn("::warning::docs/DEPENDANCES.md n'est plus à jour : lancer "
      + '`node outils/registre-dependances.mjs` et committer.');
  }
  let echec = false;
  if (nonArbitres.length) {
    console.error('::error::Licences sans arbitrage : '
      + nonArbitres.map(l => `${l.nom} (${l.licences.join(', ') || 'aucune'})`).join(' ; '));
    echec = true;
  }
  if (refuses.length) {
    console.error('::error::Composants refusés présents dans le livrable : '
      + refuses.map(l => `${l.nom} ${l.version} (${l.licences.join(', ') || 'aucune'})`).join(' ; '));
    echec = true;
  }
  if (MODELES.inconnus.length) {
    console.error('::error::Modèles Tesseract d\'origine non consignée (MODELES_TESSERACT) : '
      + MODELES.inconnus.join(' ; '));
    echec = true;
  }
  process.exit(echec ? 1 : 0);
}
writeFileSync(SORTIE, texte, 'utf-8');
console.log(`Registre écrit : ${SORTIE}`);
if (nonArbitres.length) console.warn('Licences sans arbitrage : ' + nonArbitres.map(l => l.nom).join(', '));
if (refuses.length) console.warn('Composants refusés : ' + refuses.map(l => l.nom).join(', '));
if (MODELES.inconnus.length) console.warn('Modèles Tesseract d\'origine non consignée : ' + MODELES.inconnus.join(', '));
