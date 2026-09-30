#!/usr/bin/env bash
# Recette E10 — T-028 : rejeu réduit du banc OCR (BancOcrReduit.java), hors ligne, Java seul (D5).
# Compile dans un répertoire temporaire, HORS dépôt, les seules sources livrées nécessaires
# (javac -sourcepath : ExtracteurDocumentOcr, MoteurTesseract, CorpusOcr, Scans…) contre le
# classpath d'exécution du back-end résolu par Maven hors ligne. Rien n'est écrit dans backend/.
# Usage : banc-ocr-reduit.sh [PAGES_PAR_CELLULE] [LANGUE]
# Variables : GED_TESSERACT (binaire), GED_TESSDATA (défaut backend/tessdata).

source "$(dirname "${BASH_SOURCE[0]}")/../lib/commun.sh"
PAGES="${1:-3}"; LANGUE="${2:-ara+fra}"
TESS="${GED_TESSERACT:-C:/Program Files/Tesseract-OCR/tesseract.exe}"
TD="${GED_TESSDATA:-$DEPOT_RACINE/backend/tessdata}"
[[ -f "$TESS" ]] || { resultat T-028.tesseract NA "Tesseract absent du poste" "$TESS"; bilan "T-028"; exit 2; }
B="$DEPOT_RACINE/backend"
T="$(mktemp -d "${TMPDIR:-/tmp}/qa-ocr.XXXXXX")"; trap 'rm -rf "$T"' EXIT
natif() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
SEP=":"; [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* ]] && SEP=";"
(cd "$B" && mvn -o -B -q dependency:build-classpath -Dmdep.outputFile="$(natif "$T/cp.txt")" -Dmdep.includeScope=runtime) \
  || fatal "classpath du backend introuvable hors ligne"
CP="$(cat "$T/cp.txt")"
javac -encoding UTF-8 -nowarn -proc:none -implicit:class -d "$(natif "$T/classes")" -cp "$CP" \
  -sourcepath "$(natif "$B/src/main/java")${SEP}$(natif "$B/src/test/java")" \
  "$(natif "$RECETTE_RACINE/e10/BancOcrReduit.java")" || fatal "compilation du banc"
java -Dfile.encoding=UTF-8 -cp "$(natif "$T/classes")${SEP}${CP}" com.ipt.ged.charge.BancOcrReduit "$TESS" "$(natif "$TD")" "$PAGES" "$LANGUE"
