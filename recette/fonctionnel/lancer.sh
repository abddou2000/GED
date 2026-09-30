#!/usr/bin/env bash
# Compile et lance la recette fonctionnelle (tous les .java de ce dossier + ClientGed.java) avec
# le classpath d'exécution du backend (Jackson, UnboundID), résolu HORS LIGNE par Maven.
# Usage : bash recette/fonctionnel/lancer.sh [A] [B] [C] [D]
source "$(dirname "${BASH_SOURCE[0]}")/../lib/commun.sh"
BACKEND="${BACKEND:-$DEPOT_RACINE/backend}"
T="$(mktemp -d "${TMPDIR:-/tmp}/qa2-fonc.XXXXXX")"; trap 'rm -rf "$T"' EXIT
(cd "$BACKEND" && mvn -o -B -q dependency:build-classpath -Dmdep.outputFile="$T/cp.txt" -Dmdep.includeScope=runtime) \
  || fatal "classpath du backend introuvable hors ligne"
SEP=":"; [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* ]] && SEP=";"
natif() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
CP="$(cat "$T/cp.txt")"
SOURCES=("$(natif "$RECETTE_RACINE/lib/ClientGed.java")")
for f in "$RECETTE_RACINE"/fonctionnel/*.java; do SOURCES+=("$(natif "$f")"); done
javac -encoding UTF-8 -nowarn -d "$(natif "$T/classes")" -cp "$CP" "${SOURCES[@]}" || fatal "compilation de la recette fonctionnelle"
cd "$DEPOT_RACINE" && java -Dfile.encoding=UTF-8 -cp "$(natif "$T/classes")${SEP}${CP}" RecetteFonctionnelle "$@"
