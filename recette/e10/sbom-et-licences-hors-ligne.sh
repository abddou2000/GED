#!/usr/bin/env bash
# Recette E10 — T-085 (DAT 8.3, SBOM) et P-19 (DAT 11.2, licences compatibles avec la cession).
#
# Tout se fait HORS LIGNE et HORS DÉPÔT (répertoire de travail passé en argument) :
#  1. SBOM Maven : tentative du goal CycloneDX livré (mvn -o) — le plugin exige le mode en
#     ligne ; à défaut, on prend un SBOM CycloneDX déjà produit avec un pom.xml IDENTIQUE
#     (SBOM_BACK_REF, comparé octet à octet) et on le confronte à la résolution hors ligne
#     `mvn -o dependency:list` (portée runtime) du pom de ce dépôt : mêmes composants, mêmes versions.
#  2. SBOM npm : @cyclonedx/cyclonedx-npm 6.0.1 depuis le cache npx (npx --offline, aucun
#     téléchargement), même commande que `npm run sbom`, sortie hors dépôt.
#  3. Version et licence par composant ; Tesseract et modèles tessdata.
#  4. Contrôle des licences (outils/registre-dependances.mjs --verifier) sur une COPIE, puis
#     injection d'un composant GPL-3.0-only (Maven) et AGPL-3.0-only (npm) : le contrôle doit
#     échouer (code 1). Et un composant GPL déjà « arbitré » : doit-il passer ?
#
# Usage : sbom-et-licences-hors-ligne.sh REPERTOIRE_DE_TRAVAIL [SBOM_BACK_REF]

source "$(dirname "${BASH_SOURCE[0]}")/../lib/commun.sh"
T="${1:?répertoire de travail}"; mkdir -p "$T"
SBOM_BACK_REF="${2:-/c/Users/abdou/ged-app/backend/target/bom.json}"
POM_REF="$(dirname "$(dirname "$SBOM_BACK_REF")")/pom.xml"
natif() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

# --- 1. SBOM Maven -------------------------------------------------------------------
mkdir -p "$T/maven"; cp "$DEPOT_RACINE/backend/pom.xml" "$T/maven/"
(cd "$T/maven" && mvn -o -B -ntp org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom > "$T/mvn-cyclonedx.log" 2>&1)
if [[ -f "$T/maven/target/bom.json" ]]; then
  cp "$T/maven/target/bom.json" "$T/bom-back.json"
  resultat T-085.maven OK "SBOM CycloneDX Maven produit hors ligne" ""
else
  resultat T-085.maven NA "goal CycloneDX Maven refusé hors ligne" "$(grep -m1 '\[ERROR\]' "$T/mvn-cyclonedx.log" | cut -c1-300)"
  if cmp -s "$DEPOT_RACINE/backend/pom.xml" "$POM_REF" && [[ -f "$SBOM_BACK_REF" ]]; then
    cp "$SBOM_BACK_REF" "$T/bom-back.json"
    info "SBOM de référence (pom identique) : $SBOM_BACK_REF"
  else
    fatal "aucun SBOM Maven de référence produit avec un pom identique"
  fi
fi
(cd "$DEPOT_RACINE/backend" && mvn -o -B -q dependency:list -DincludeScope=runtime -DexcludeTransitive=false \
    -DoutputFile="$(natif "$T/dependances-runtime.txt")" -DappendOutput=false) || fatal "dependency:list hors ligne"
node - "$(natif "$T/bom-back.json")" "$(natif "$T/dependances-runtime.txt")" > "$T/comparaison-maven.txt" <<'JS'
const fs = require('fs');
const bom = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
// Composants hors gestionnaire (pkg:generic : Tesseract et modèles, ANO-E0-002) : absents de la résolution Maven par nature.
const sbom = new Set(bom.components.filter(c => !(c.purl || '').startsWith('pkg:generic/')).map(c => `${c.group}:${c.name}:${c.version}`));
const liste = new Set(fs.readFileSync(process.argv[3], 'utf8').split(/\r?\n/)
  .map(l => l.trim().match(/^([^:\s]+):([^:]+):[^:]+(?::[^:]+)?:([^:]+):(compile|runtime)/))
  .filter(Boolean).map(m => `${m[1]}:${m[2]}:${m[3]}`));
const manquants = [...liste].filter(x => !sbom.has(x)), enTrop = [...sbom].filter(x => !liste.has(x));
console.log(`sbom=${sbom.size} resolution=${liste.size} absents_du_sbom=${manquants.length} absents_de_la_resolution=${enTrop.length}`);
manquants.forEach(x => console.log('ABSENT_SBOM ' + x)); enTrop.forEach(x => console.log('EN_TROP ' + x));
JS
ligne=$(head -1 "$T/comparaison-maven.txt")
if grep -q 'absents_du_sbom=0 absents_de_la_resolution=0' <<<"$ligne"; then
  resultat T-085.maven-coherence OK "SBOM Maven = résolution runtime hors ligne du pom (groupe:nom:version)" "$ligne"
else
  resultat T-085.maven-coherence AVERT "SBOM Maven et résolution runtime divergent" "$ligne"
fi

# --- 2. SBOM npm --------------------------------------------------------------------
(cd "$DEPOT_RACINE/frontend" && npx --offline --yes @cyclonedx/cyclonedx-npm@6.0.1 --omit dev --output-reproducible \
    --spec-version 1.6 --output-format JSON --output-file "$(natif "$T/bom-front.json")" > "$T/npm-sbom.log" 2>&1)
if [[ -s "$T/bom-front.json" ]]; then
  resultat T-085.npm OK "SBOM CycloneDX npm produit hors ligne (cache npx)" ""
else
  resultat T-085.npm NA "SBOM npm non produit hors ligne" "$(tail -3 "$T/npm-sbom.log" | tr '\n' ' ' | cut -c1-300)"
fi

# --- 3. Version et licence par composant ----------------------------------------------
for f in bom-back bom-front; do
  [[ -f "$T/$f.json" ]] || continue
  node - "$(natif "$T/$f.json")" > "$T/$f-licences.txt" <<'JS'
const bom = JSON.parse(require('fs').readFileSync(process.argv[2], 'utf8'));
let sansVersion = 0, sansLicence = 0; const parLicence = {};
for (const c of bom.components) {
  if (!c.version) sansVersion++;
  const l = (c.licenses || []).map(x => x.expression || x.license?.id || x.license?.name).filter(Boolean);
  if (!l.length) { sansLicence++; console.log('SANS_LICENCE ' + (c.group ? c.group + ':' : '') + c.name + '@' + c.version); }
  const k = l.join(' | ') || '(aucune)'; parLicence[k] = (parLicence[k] || 0) + 1;
}
console.log(`TOTAL composants=${bom.components.length} sans_version=${sansVersion} sans_licence=${sansLicence}`);
for (const [k, n] of Object.entries(parLicence).sort((a, b) => b[1] - a[1])) console.log(`LICENCE ${n}\t${k}`);
const t = bom.components.filter(c => /tesseract|tessdata/i.test(c.name + ' ' + (c.group || '')));
console.log('TESSERACT_DANS_SBOM ' + t.length);
// T-085 : le moteur ET les quatre modèles livrés (fra, ara, eng, osd), avec version et empreinte pour les modèles.
const attendus = ['tesseract', 'tessdata-fra', 'tessdata-ara', 'tessdata-eng', 'tessdata-osd'];
const manquants = attendus.filter(n => !t.some(c => c.name === n && c.version));
const sansEmpreinte = t.filter(c => c.name.startsWith('tessdata') && !(c.hashes || []).some(h => h.alg === 'SHA-256'));
console.log('TESSERACT_MANQUANTS ' + (manquants.join(',') || 'aucun') + ' SANS_EMPREINTE ' + sansEmpreinte.length);
JS
  tot=$(grep '^TOTAL' "$T/$f-licences.txt")
  if grep -q 'sans_version=0 sans_licence=0' <<<"$tot"; then
    resultat "T-085.$f" OK "version et licence renseignées pour chaque composant" "$tot"
  else
    resultat "T-085.$f" AVERT "composants sans version ou sans licence" "$tot"
  fi
  tess=$(grep '^TESSERACT_DANS_SBOM' "$T/$f-licences.txt" | cut -d' ' -f2)
  manq=$(grep '^TESSERACT_MANQUANTS' "$T/$f-licences.txt")
  [[ "$f" == bom-back ]] && { [[ "$tess" -gt 0 && "$manq" == "TESSERACT_MANQUANTS aucun SANS_EMPREINTE 0" ]] && resultat T-085.tesseract-sbom OK "Tesseract et modèles fra, ara, eng, osd présents dans le SBOM (version, empreinte SHA-256)" "$manq" \
      || resultat T-085.tesseract-sbom AVERT "Tesseract et modèles absents du SBOM CycloneDX (tracés seulement dans docs/DEPENDANCES.md)" ""; }
done

# --- 4. Contrôle des licences (P-19) sur une copie -------------------------------------
controle() {  # controle <nom> <bom-back> <bom-front> : code de sortie du --verifier sur une copie
  local R="$T/registre-$1"; rm -rf "$R"; mkdir -p "$R/outils" "$R/backend/target" "$R/frontend/dist" "$R/docs"
  # Tous les modules de outils/ : registre-dependances.mjs importe composants-hors-gestionnaire.mjs (tour 2).
  cp "$DEPOT_RACINE"/outils/*.mjs "$R/outils/"; cp "$DEPOT_RACINE/docs/DEPENDANCES.md" "$R/docs/"
  ln -s "$DEPOT_RACINE/backend/tessdata" "$R/backend/tessdata"   # modèles lus par le registre (empreintes)
  cp "$2" "$R/backend/target/bom.json"; cp "$3" "$R/frontend/dist/bom.json"
  node "$(natif "$R/outils/registre-dependances.mjs")" --verifier > "$R/sortie.txt" 2>&1; echo $?
}
refus() {  # refus <nom> <code> <motif> : vrai si le contrôle échoue (1) EN CITANT le composant injecté
  [[ "$2" == 1 ]] && grep -q -- "$3" "$T/registre-$1/sortie.txt" && ! grep -q 'ERR_MODULE_NOT_FOUND' "$T/registre-$1/sortie.txt"
}
injecter() {  # injecter <source> <cible> <group> <name> <licenceSPDX>
  node - "$(natif "$1")" "$(natif "$2")" "$3" "$4" "$5" <<'JS'
const fs = require('fs'); const [src, dst, g, n, lic] = process.argv.slice(2);
const bom = JSON.parse(fs.readFileSync(src, 'utf8'));
const ref = `pkg:${g ? 'maven/' + g : 'npm'}/${n}@9.9.9`;
bom.components.push({ type: 'library', 'bom-ref': ref, group: g || undefined, name: n, version: '9.9.9',
  licenses: [{ license: { id: lic } }] });
const racine = bom.metadata.component['bom-ref'];
const d = bom.dependencies.find(x => x.ref === racine); d.dependsOn = [...(d.dependsOn || []), ref];
fs.writeFileSync(dst, JSON.stringify(bom));
JS
}
if [[ -f "$T/bom-back.json" && -f "$T/bom-front.json" ]]; then
  c=$(controle nominal "$T/bom-back.json" "$T/bom-front.json")
  [[ "$c" == 0 ]] && resultat P-19.nominal OK "licences arbitrées : --verifier passe" "$(tr '\n' ' ' < "$T/registre-nominal/sortie.txt" | cut -c1-200)" \
    || resultat P-19.nominal AVERT "--verifier échoue sur les SBOM actuels (code $c)" "$(tr '\n' ' ' < "$T/registre-nominal/sortie.txt" | cut -c1-300)"
  injecter "$T/bom-back.json" "$T/bom-back-gpl.json" com.exemple.recette bibliotheque-gpl GPL-3.0-only
  c=$(controle gpl-maven "$T/bom-back-gpl.json" "$T/bom-front.json")
  refus gpl-maven "$c" bibliotheque-gpl && resultat P-19.gpl-maven OK "composant Maven GPL-3.0-only non arbitré : contrôle en échec (code 1)" "$(grep -m1 'error' "$T/registre-gpl-maven/sortie.txt" | cut -c1-200)" \
    || resultat P-19.gpl-maven ECHEC "composant GPL-3.0-only accepté (code $c)" ""
  injecter "$T/bom-front.json" "$T/bom-front-agpl.json" "" paquet-agpl AGPL-3.0-only
  c=$(controle agpl-npm "$T/bom-back.json" "$T/bom-front-agpl.json")
  refus agpl-npm "$c" paquet-agpl && resultat P-19.agpl-npm OK "paquet npm AGPL-3.0-only non arbitré : contrôle en échec (code 1)" "" \
    || resultat P-19.agpl-npm ECHEC "paquet AGPL-3.0-only accepté (code $c)" ""
  # GPL sous un groupe déjà arbitré : le préfixe « org.verapdf: » force MPL-2.0 pour tout le groupe.
  injecter "$T/bom-back.json" "$T/bom-back-verapdf-gpl.json" org.verapdf composant-gpl-seul GPL-3.0-only
  c=$(controle verapdf-gpl "$T/bom-back-verapdf-gpl.json" "$T/bom-front.json")
  refus verapdf-gpl "$c" composant-gpl-seul && resultat P-19.groupe-arbitre OK "GPL-3.0-only seul dans le groupe org.verapdf : refusé" "" \
    || resultat P-19.groupe-arbitre AVERT "GPL-3.0-only seul dans le groupe org.verapdf : ACCEPTÉ (code $c) — l'arbitrage par groupe écrase la licence déclarée" ""
  # Composant GPL arbitré par clé exacte (mysql-connector-j, arbitrage « risque réel, ne pas livrer »).
  injecter "$T/bom-back.json" "$T/bom-back-mysql.json" com.mysql mysql-connector-j GPL-2.0-with-universal-foss-exception
  c=$(controle mysql "$T/bom-back-mysql.json" "$T/bom-front.json")
  refus mysql "$c" mysql-connector-j && resultat P-19.arbitre-risque OK "mysql-connector-j (GPL) refusé" "" \
    || resultat P-19.arbitre-risque AVERT "mysql-connector-j (GPL, arbitrage « ne pas livrer en production ») ACCEPTÉ par le contrôle (code $c)" ""
fi
bilan "T-085 / P-19 SBOM et licences"
