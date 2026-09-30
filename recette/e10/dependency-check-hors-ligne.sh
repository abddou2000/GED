#!/usr/bin/env bash
# Recette E10 — T-070 (DAT 6.2.3, A06) : tentative d'exécution HORS LIGNE d'OWASP
# Dependency-Check avec la configuration du pom.xml livré (seuil ged.cvss.seuil).
#
# Aucun accès réseau : Maven en mode -o, mise à jour NVD coupée (autoUpdate=false),
# analyseurs à requête distante coupés (Central, suppressions hébergées, CISA KEV,
# Nexus, Artifactory, vérification de version). Le pom et le fichier de suppressions
# sont COPIÉS dans un répertoire de travail hors dépôt : rien n'est écrit dans backend/.
#
# Usage : dependency-check-hors-ligne.sh [REPERTOIRE_DE_TRAVAIL]
# Sortie : lignes RESULTAT| (lib/commun.sh) et journal Maven complet dans le répertoire.

source "$(dirname "${BASH_SOURCE[0]}")/../lib/commun.sh"
TRAVAIL="${1:-$(mktemp -d "${TMPDIR:-/tmp}/qa-dc.XXXXXX")}"
mkdir -p "$TRAVAIL"
cp "$DEPOT_RACINE/backend/pom.xml" "$DEPOT_RACINE/backend/dependency-check-suppressions.xml" "$TRAVAIL/" \
  || fatal "copie du pom impossible"
info "répertoire de travail : $TRAVAIL"

# Configuration livrée (lecture du pom).
seuil=$(grep -oE '<ged.cvss.seuil>[0-9.]+' "$TRAVAIL/pom.xml" | cut -d'>' -f2)
if grep -q '<failBuildOnCVSS>${ged.cvss.seuil}</failBuildOnCVSS>' "$TRAVAIL/pom.xml" && [[ -n "$seuil" ]]; then
  resultat T-070.seuil OK "failBuildOnCVSS lié à ged.cvss.seuil" "seuil=$seuil"
else
  resultat T-070.seuil ECHEC "failBuildOnCVSS absent ou non lié" ""
fi
if grep -A3 '<id>analyse-vulnerabilites</id>' "$TRAVAIL/pom.xml" | grep -q '<phase>verify</phase>'; then
  resultat T-070.phase OK "goal check lié à la phase verify" ""
else
  resultat T-070.phase ECHEC "goal check non lié à verify" ""
fi

(cd "$TRAVAIL" && mvn -o -B -ntp org.owasp:dependency-check-maven:12.2.2:check \
    -DautoUpdate=false -DcentralAnalyzerEnabled=false -DhostedSuppressionsEnabled=false \
    -DknownExploitedEnabled=false -DversionCheckEnabled=false -DnexusAnalyzerEnabled=false \
    -DartifactoryAnalyzerEnabled=false > "$TRAVAIL/mvn-dependency-check.log" 2>&1)
code=$?
info "code de sortie Maven : $code (journal : $TRAVAIL/mvn-dependency-check.log)"
if [[ $code -eq 0 ]]; then
  resultat T-070.execution OK "analyse exécutée hors ligne" "$(ls "$TRAVAIL"/target/dependency-check-report.* 2>/dev/null | tr '\n' ' ')"
elif grep -qE "One or more dependencies were identified with vulnerabilities that have a CVSS score greater than or equal to" "$TRAVAIL/mvn-dependency-check.log"; then
  resultat T-070.execution AVERT "analyse exécutée, construction ÉCHOUÉE par le seuil CVSS" \
    "$(grep -m1 -E 'CVSS score greater' "$TRAVAIL/mvn-dependency-check.log")"
else
  resultat T-070.execution NA "analyse non exécutable hors ligne" \
    "$(grep -m3 -E '\[ERROR\]' "$TRAVAIL/mvn-dependency-check.log" | tr '\n' ' ' | cut -c1-600)"
fi
bilan "T-070 dependency-check hors ligne"
