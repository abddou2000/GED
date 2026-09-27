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

const racine = join(dirname(fileURLToPath(import.meta.url)), '..');
const SBOM_BACK = join(racine, 'backend/target/bom.json');
const SBOM_FRONT = join(racine, 'frontend/dist/bom.json');
const SORTIE = join(racine, 'docs/DEPENDANCES.md');

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

/* Composants tiers qui ne passent par aucun gestionnaire de paquets et
   n'apparaissent donc dans aucun SBOM : le DAT 8.3 exige pourtant de les
   tracer (« moteur OCR notamment »). */
const HORS_GESTIONNAIRE = [
  { nom: 'Tesseract OCR', version: '5.x (binaire du serveur)', licence: 'Apache-2.0',
    usage: 'Moteur OCR, appelé en processus externe (DAT 4.3.1)' },
  { nom: 'Modèles Tesseract (fra, eng, osd)', version: 'non tracée (dépôt tesseract-ocr/tessdata)',
    licence: 'Apache-2.0', usage: 'Modèles LSTM livrés dans backend/tessdata ; version à consigner (E6, ajout de ara)' },
  { nom: 'Icônes lucide-static', version: '1.26.0', licence: 'ISC',
    usage: 'Tracés SVG recopiés dans frontend/src/app/core/ged-icons.ts' },
];

/* Licences dont la déclaration du paquet est illisible par l'outil mais
   vérifiée à la main dans le fichier LICENSE du paquet. */
const LICENCES_VERIFIEES = {
  ':primeicons': 'MIT',
};

/* Double licence dont l'option est retenue pour tout un groupe (préfixe « groupe: »). */
const LICENCES_RETENUES_GROUPES = {
  // veraPDF : GPL-3.0-or-later OU MPL-2.0-or-later ; MPL-2.0 retenue (lot E7, dev3).
  'org.verapdf:': 'MPL-2.0',
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
  const groupe = Object.keys(LICENCES_RETENUES_GROUPES).find(p => cle.startsWith(p));
  if (groupe) return { ids: [LICENCES_RETENUES_GROUPES[groupe]], verifiee: true };
  const ids = (composant.licenses || []).map(l =>
    l.expression ? l.expression : normaliser(l.license?.id || l.license?.name)).filter(Boolean);
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

  return (bom.components || []).map(c => {
    const cle = nomDe(c);
    const { ids, verifiee } = licencesDe(c, cle);
    const direct = directs.has(c['bom-ref']);
    const amenePar = parent.get(c['bom-ref']);
    return {
      cle,
      nom: ecosysteme === 'npm' ? (c.group ? `${c.group}/${c.name}` : c.name) : `${c.group}:${c.name}`,
      version: c.version || '',
      licences: ids,
      verifiee,
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
    t.push(`| ${echapper(l.nom)} | ${echapper(l.version)} | ${echapper(lic)} | ${LIBELLE_CLASSE[l.classe]} | ${echapper(l.usage)} |`);
  }
  return t.join('\n');
}

/* Arbitrages écrits pour chaque composant « à examiner » ou sous condition :
   un registre qui liste un risque sans le trancher ne sert à rien. */
const ARBITRAGES = {
  'com.mysql:mysql-connector-j': 'GPL-2.0 avec exception FOSS universelle : l\'exception couvre l\'usage avec un logiciel sous licence libre, **pas une application propriétaire cédée à MMED**. Risque réel. Le composant disparaît avec la migration PostgreSQL (E1, dev1) : à retirer du `pom.xml` à ce moment, et ne pas livrer en production avant.',
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

/** Arbitrage d'un composant : clé exacte, sinon celui de son groupe (clé « groupe: »). */
function arbitrage(cle) {
  if (ARBITRAGES[cle]) return ARBITRAGES[cle];
  const groupe = Object.keys(ARBITRAGES).find(p => p.endsWith(':') && cle.startsWith(p));
  return groupe ? ARBITRAGES[groupe] : null;
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
  md.push(`| **À examiner** | ${compte('a-examiner')} |`, '');
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
    const arb = arbitrage(l.cle) || '_Arbitrage à rédiger._';
    md.push(`- **${l.nom} ${l.version}** (${l.licences.join(' ou ') || 'licence non déclarée'}) — ${arb}`);
  }
  md.push('');
  md.push('## Composants hors gestionnaire de paquets', '');
  md.push('| Nom | Version | Licence | Usage |', '|---|---|---|---|');
  for (const h of HORS_GESTIONNAIRE) md.push(`| ${h.nom} | ${h.version} | ${h.licence} | ${h.usage} |`);
  md.push('');
  md.push('## Back-end — dépendances directes', '', tableau(back.filter(l => l.direct)), '');
  md.push('## Front-end — dépendances directes livrées', '', tableau(front.filter(l => l.direct)), '');
  md.push('## Back-end — dépendances transitives', '', tableau(back.filter(l => !l.direct)), '');
  md.push('## Front-end — dépendances transitives livrées', '', tableau(front.filter(l => !l.direct)), '');
  return { texte: md.join('\n'), nonArbitres: sensibles.filter(l => !arbitrage(l.cle)) };
}

const { texte, nonArbitres } = generer();
if (process.argv.includes('--verifier')) {
  const actuel = existsSync(SORTIE) ? readFileSync(SORTIE, 'utf-8').replace(/\r\n/g, '\n') : '';
  /* Un registre en retard est signalé sans bloquer : le SBOM, produit à
     chaque construction, reste la source de vérité. Une licence non arbitrée,
     elle, bloque : c'est un risque juridique pour la cession à MMED. */
  if (actuel !== texte) {
    console.warn("::warning::docs/DEPENDANCES.md n'est plus à jour : lancer "
      + '`node outils/registre-dependances.mjs` et committer.');
  }
  if (nonArbitres.length) {
    console.error('::error::Licences sans arbitrage : '
      + nonArbitres.map(l => `${l.nom} (${l.licences.join(', ') || 'aucune'})`).join(' ; '));
    process.exit(1);
  }
  process.exit(0);
}
writeFileSync(SORTIE, texte, 'utf-8');
console.log(`Registre écrit : ${SORTIE}`);
if (nonArbitres.length) console.warn('Licences sans arbitrage : ' + nonArbitres.map(l => l.nom).join(', '));
