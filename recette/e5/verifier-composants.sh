#!/usr/bin/env bash
# Recette E5 au niveau des composants (vague 1 : stockage chiffré pas encore branché sur
# le dépôt HTTP). Lance BancComposantsE5.java avec les classes de production du backend
# (target/classes, construites par « mvn test » ou « mvn compile test-compile ») et le
# clamd factice des tests de dev3 (target/test-classes), puis fait contrôler le stockage
# produit par verifier-aucun-clair.sh.
#
# Usage : verifier-composants.sh [--clamd hote:port]   (ClamAV réel en UAT)
# Prérequis : backend compilé (mvn -o test-compile), JDK 17, Maven hors ligne.

source "$(dirname "${BASH_SOURCE[0]}")/../lib/commun.sh"
ICI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND="${BACKEND:-$DEPOT_RACINE/backend}"
[[ -d "$BACKEND/target/classes/com/ipt/ged/fichier" && -d "$BACKEND/target/test-classes" ]] \
  || fatal "backend non compilé : cd backend && mvn -o test-compile"
TRAVAIL="$(mktemp -d "${TMPDIR:-/tmp}/qa-e5-composants.XXXXXX")"
CP_FICHIER="$TRAVAIL/cp.txt"
(cd "$BACKEND" && mvn -o -B -q dependency:build-classpath -Dmdep.outputFile="$CP_FICHIER" -Dmdep.includeScope=test) \
  || fatal "classpath du backend introuvable hors ligne"
SEP=":"; [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* ]] && SEP=";"
natif() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
CP="$(natif "$BACKEND/target/classes")${SEP}$(natif "$BACKEND/target/test-classes")${SEP}$(cat "$CP_FICHIER")"
java_source -cp "$CP" "$ICI/BancComposantsE5.java" --donnees "$(natif "$RECETTE_RACINE/donnees")" \
  --travail "$(natif "$TRAVAIL")" --scanner "$ICI/verifier-aucun-clair.sh" "$@"
code=$?
info "stockage de recette conservé dans $TRAVAIL (à supprimer après examen)"
exit $code
