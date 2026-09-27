#!/usr/bin/env bash
# Compile et lance une recette Java avec ClientGed.java et le classpath d'exécution du backend
# (Jackson, pilote PostgreSQL), résolu HORS LIGNE par Maven. Aucun artefact dans le dépôt.
# Usage : lancer-java.sh SOURCE.java CLASSE_PRINCIPALE [arguments…]

source "$(dirname "${BASH_SOURCE[0]}")/commun.sh"
SOURCE="$1"; CLASSE="$2"; shift 2
BACKEND="${BACKEND:-$DEPOT_RACINE/backend}"
T="$(mktemp -d "${TMPDIR:-/tmp}/qa-java.XXXXXX")"; trap 'rm -rf "$T"' EXIT
(cd "$BACKEND" && mvn -o -B -q dependency:build-classpath -Dmdep.outputFile="$T/cp.txt" -Dmdep.includeScope=runtime) \
  || fatal "classpath du backend introuvable hors ligne"
SEP=":"; [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* ]] && SEP=";"
natif() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
CP="$(cat "$T/cp.txt")"
javac -encoding UTF-8 -nowarn -d "$(natif "$T/classes")" -cp "$CP" "$(natif "$RECETTE_RACINE/lib/ClientGed.java")" "$(natif "$SOURCE")" \
  || fatal "compilation de $SOURCE"
java -Dfile.encoding=UTF-8 -cp "$(natif "$T/classes")${SEP}${CP}" "$CLASSE" "$@"
