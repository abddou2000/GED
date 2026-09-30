#!/bin/bash
# =====================================================================
#  GED Marchica Med — essai d'OWASP Dependency-Check en mode MIROIR
#  (DAT 6.2.3 A06, T-070), sans accès à la NVD ni clé NVD_API_KEY.
#
#  Le script sert, sur 127.0.0.1, un miroir de données au format des flux
#  NVD 2.0 (celui que produisent vulnz / Open Vulnerability CLI et que lit
#  `-DnvdDatafeedUrl`) contenant DEUX VULNÉRABILITÉS SYNTHÉTIQUES sur
#  tika-core (CVE-2099-0001, CVSS 9,8 ; CVE-2099-0002, CVSS 5,3 ; ces
#  identifiants n'existent pas). Il lance ensuite le plugin tel que le
#  configure backend/pom.xml, dans une base de données jetable, et vérifie :
#    1. la chaîne miroir -> base H2 -> analyse -> rapports HTML et JSON ;
#    2. le seuil de blocage : CVSS >= ged.cvss.seuil (7) fait échouer le build ;
#    3. une vulnérabilité sous le seuil est rapportée sans bloquer ;
#    4. une suppression datée (règle de dependency-check-suppressions.xml)
#       lève le blocage, la vulnérabilité restant tracée comme supprimée.
#  Il NE dit RIEN des vulnérabilités réelles des dépendances : l'analyse
#  réelle est celle du job CI « OWASP Dependency-Check (CVSS >= 7) », avec
#  la clé NVD ou un miroir réel (docs/securite/VULNERABILITES-DEPENDANCES.md).
#
#  Prérequis : Maven (plugin téléchargé depuis Maven Central), Node.js.
#  Usage : bash outils/essai-dependency-check.sh      (depuis la racine)
#  GARDER=1 : répertoire de travail conservé (rapports, journaux Maven).
#  Code de sortie : 0 si tous les contrôles passent.
# =====================================================================
set -u
RACINE=$(cd "$(dirname "$0")/.." && pwd)
D=$(mktemp -d /tmp/ged-essai-dc.XXXXXX)
SERVEUR=
arreter() {
    [ -n "$SERVEUR" ] && kill "$SERVEUR" 2> /dev/null
    if [ -n "${GARDER:-}" ]; then echo "conservé : $D"; else rm -rf "$D"; fi
}
trap arreter EXIT

ECHECS=0
ok()   { echo "  [OK]    $1"; }
ko()   { echo "  [ÉCHEC] $1"; ECHECS=$((ECHECS + 1)); }

# ---------------------------------------------------------------------
# 1. Miroir synthétique au format des flux NVD 2.0
# ---------------------------------------------------------------------
mkdir -p "$D/miroir" "$D/donnees"
ANNEE=$(date -u +%Y)
MAINTENANT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
cve() { # identifiant, score, sévérité, impacts C I A (HIGH|LOW|NONE)
    cat <<JSON
{"cve":{"id":"$1","sourceIdentifier":"essai@ged.invalid","published":"${ANNEE}-01-01T00:00:00.000",
 "lastModified":"${ANNEE}-01-01T00:00:00.000","vulnStatus":"Analyzed",
 "descriptions":[{"lang":"en","value":"ESSAI SYNTHETIQUE GED (T-070) : vulnerabilite fictive, n'existe pas."}],
 "metrics":{"cvssMetricV31":[{"source":"nvd@nist.gov","type":"Primary","cvssData":{"version":"3.1",
  "vectorString":"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:${4:0:1}/I:${5:0:1}/A:${6:0:1}","baseScore":$2,"baseSeverity":"$3",
  "attackVector":"NETWORK","attackComplexity":"LOW","privilegesRequired":"NONE","userInteraction":"NONE","scope":"UNCHANGED",
  "confidentialityImpact":"$4","integrityImpact":"$5","availabilityImpact":"$6"},"exploitabilityScore":3.9,"impactScore":5.9}]},
 "weaknesses":[{"source":"nvd@nist.gov","type":"Primary","description":[{"lang":"en","value":"CWE-20"}]}],
 "configurations":[{"nodes":[{"operator":"OR","negate":false,"cpeMatch":[{"vulnerable":true,
  "criteria":"cpe:2.3:a:apache:tika:*:*:*:*:*:*:*:*","versionEndExcluding":"99.0.0",
  "matchCriteriaId":"00000000-0000-4000-8000-00000000000${1: -1}"}]}]}],
 "references":[{"url":"https://essai.ged.invalid/$1","source":"essai@ged.invalid"}]}}
JSON
}
flux() { # fichier, éléments JSON
    printf '{"resultsPerPage":%s,"startIndex":0,"totalResults":%s,"format":"NVD_CVE","version":"2.0","timestamp":"%s","vulnerabilities":[%s]}' \
        "$2" "$2" "${MAINTENANT%Z}.000" "$3" | gzip -c > "$D/miroir/$1"
}
flux "nvdcve-${ANNEE}.json.gz" 2 "$(cve CVE-2099-0001 9.8 CRITICAL HIGH HIGH HIGH),$(cve CVE-2099-0002 5.3 MEDIUM LOW NONE NONE)"
flux "nvdcve-modified.json.gz" 0 ""
# Même contenu que le cache.properties d'un miroir vulnz (dates des flux).
{
    echo "prefix=nvdcve-"
    echo "lastModifiedDate=${MAINTENANT//:/\\:}"
    echo "lastModifiedDate.modified=${MAINTENANT//:/\\:}"
    echo "lastModifiedDate.${ANNEE}=${MAINTENANT//:/\\:}"
} > "$D/miroir/cache.properties"

# Serveur HTTP local (Node.js, aucun Python : décision D5), port libre.
node -e '
const http = require("http"), fs = require("fs"), path = require("path");
const racine = process.argv[1];
const s = http.createServer((q, r) => {
  const f = path.join(racine, path.basename(decodeURIComponent(q.url.split("?")[0])));
  fs.readFile(f, (e, d) => { if (e) { r.writeHead(404); r.end(); } else { r.writeHead(200); r.end(d); } });
});
s.listen(0, "127.0.0.1", () => fs.writeFileSync(path.join(racine, "..", "port"), String(s.address().port)));
' "$D/miroir" &
SERVEUR=$!
for _ in $(seq 50); do [ -s "$D/port" ] && break; sleep 0.1; done
PORT=$(cat "$D/port" 2> /dev/null)
[ -n "$PORT" ] || { echo "serveur du miroir non démarré"; exit 2; }
echo "Miroir synthétique servi sur http://127.0.0.1:$PORT/ (flux ${ANNEE} et modified)"

# ---------------------------------------------------------------------
# 2. Analyse avec la configuration du pom (seuil, formats, suppressions)
# ---------------------------------------------------------------------
# Options du mode miroir : toutes les sources en ligne autres que le miroir
# sont coupées (la liste KEV de la CISA et les suppressions hébergées se
# servent aussi depuis un miroir interne en exploitation : voir la doc).
OPTIONS=(-B -ntp -f "$RACINE/backend/pom.xml" -DskipTests
    "-DnvdDatafeedUrl=http://127.0.0.1:$PORT/nvdcve-{0}.json.gz"
    "-DnvdDatafeedStartYear=$ANNEE"
    "-DdataDirectory=$D/donnees"
    -DknownExploitedEnabled=false -DhostedSuppressionsEnabled=false
    -DcentralAnalyzerEnabled=false
    "-Dodc.outputDirectory=$D/rapport")

analyser() { # journal, options supplémentaires
    local j=$1; shift
    mvn "${OPTIONS[@]}" "$@" org.owasp:dependency-check-maven:check > "$D/$j" 2>&1
}
rapport_contient() { # identifiant, champ attendu (vulnerabilities|suppressedVulnerabilities)
    node -e '
const r = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"));
const trouve = r.dependencies.some(d => d.fileName.startsWith("tika-core-")
    && (d[process.argv[3]] || []).some(v => v.name === process.argv[2]));
process.exit(trouve ? 0 : 1);' "$D/rapport/dependency-check-report.json" "$1" "$2"
}

echo "Analyse 1 : configuration du pom, seuil CVSS 7"
analyser analyse-1.log
code=$?
[ -s "$D/rapport/dependency-check-report.json" ] && ok "rapport JSON produit" || ko "rapport JSON absent (voir $D/analyse-1.log)"
[ -s "$D/rapport/dependency-check-report.html" ] && ok "rapport HTML produit" || ko "rapport HTML absent"
if ls "$D/donnees"/*.mv.db > /dev/null 2>&1; then ok "base H2 alimentée depuis le miroir"; else ko "base H2 absente"; fi
[ "$code" -ne 0 ] && ok "build en échec (CVSS 9,8 >= 7)" || ko "build réussi malgré une vulnérabilité CVSS 9,8"
grep -q "CVE-2099-0001" "$D/analyse-1.log" && ok "journal Maven : CVE-2099-0001 cité comme cause de l'échec" || ko "CVE-2099-0001 absent du journal Maven"
rapport_contient CVE-2099-0001 vulnerabilities && ok "rapport : CVE-2099-0001 sur tika-core" || ko "rapport : CVE-2099-0001 absent"
rapport_contient CVE-2099-0002 vulnerabilities && ok "rapport : CVE-2099-0002 (CVSS 5,3, sous le seuil) rapporté" || ko "rapport : CVE-2099-0002 absent"
cp "$D/rapport/dependency-check-report.json" "$D/rapport-1.json" 2> /dev/null

echo "Analyse 2 : suppression datée de CVE-2099-0001 (règle d'équipe)"
FIN=$(date -u -d "+90 days" +%Y-%m-%dZ)
cat > "$D/suppression-essai.xml" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<suppressions xmlns="https://jeremylong.github.io/DependencyCheck/dependency-suppression.1.3.xsd">
  <suppress until="$FIN">
    <notes>ESSAI T-070 : vulnérabilité synthétique ; référence exacte, raison, réexamen daté.</notes>
    <cve>CVE-2099-0001</cve>
  </suppress>
</suppressions>
XML
analyser analyse-2.log -DautoUpdate=false "-DsuppressionFile=$D/suppression-essai.xml"
code=$?
[ "$code" -eq 0 ] && ok "build réussi : plus aucune vulnérabilité non supprimée >= 7" || ko "build en échec malgré la suppression (voir $D/analyse-2.log)"
rapport_contient CVE-2099-0001 suppressedVulnerabilities && ok "rapport : CVE-2099-0001 tracé comme supprimé" || ko "rapport : CVE-2099-0001 non tracé comme supprimé"
rapport_contient CVE-2099-0002 vulnerabilities && ok "rapport : CVE-2099-0002 toujours rapporté" || ko "rapport : CVE-2099-0002 absent"

echo
if [ "$ECHECS" -eq 0 ]; then echo "Essai Dependency-Check en mode miroir : tous les contrôles passent."; else echo "Essai Dependency-Check : $ECHECS contrôle(s) en échec."; fi
exit $((ECHECS > 0))
