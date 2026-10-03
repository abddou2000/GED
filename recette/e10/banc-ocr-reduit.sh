#!/usr/bin/env bash
# Recette E10 — T-028 / P-14 : rejeu réduit du banc OCR (BancOcrReduit.java), hors ligne, Java seul (D5).
# Compile dans un répertoire temporaire, HORS dépôt, les seules sources livrées nécessaires
# (javac -sourcepath : ExtracteurDocumentOcr, MoteurTesseract, CorpusOcr, Scans…) contre le
# classpath d'exécution du back-end résolu par Maven hors ligne. Rien n'est écrit dans backend/.
# Usage : banc-ocr-reduit.sh [PAGES_PAR_CELLULE] [LANGUE] [DPI]
#   DPI : résolution du rendu des PDF (défaut 300 = vagues 8 à 11 ; 200 = réglage livré au tour 6).
# Variables : GED_TESSERACT (binaire), GED_TESSDATA (défaut backend/tessdata),
#   GED_BANC_MODELES=precis|entiers (défaut precis) : « entiers » compacte, HORS dépôt et
#   indépendamment de l'application, une copie des modèles par `combine_tessdata -c`
#   (GED_COMBINE_TESSDATA, défaut : à côté de GED_TESSERACT) et mesure sur cette copie ;
#   les empreintes SHA-256 de la copie sont affichées (comparables à celles de l'application).

source "$(dirname "${BASH_SOURCE[0]}")/../lib/commun.sh"
PAGES="${1:-3}"; LANGUE="${2:-ara+fra}"; DPI="${3:-300}"
TESS="${GED_TESSERACT:-C:/Program Files/Tesseract-OCR/tesseract.exe}"
TD="${GED_TESSDATA:-$DEPOT_RACINE/backend/tessdata}"
MODELES="${GED_BANC_MODELES:-precis}"
[[ -f "$TESS" ]] || { resultat T-028.tesseract NA "Tesseract absent du poste" "$TESS"; bilan "T-028"; exit 2; }
B="$DEPOT_RACINE/backend"
T="$(mktemp -d "${TMPDIR:-/tmp}/qa-ocr.XXXXXX")"; trap 'rm -rf "$T"' EXIT
natif() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
SEP=":"; [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* ]] && SEP=";"
case "$MODELES" in
  precis) ;;
  entiers)
    CT="${GED_COMBINE_TESSDATA:-$(dirname "$TESS")/combine_tessdata}"
    [[ "$TESS" == *.exe && -z "${GED_COMBINE_TESSDATA:-}" ]] && CT="$(dirname "$TESS")/combine_tessdata.exe"
    [[ -f "$CT" ]] || fatal "combine_tessdata introuvable : $CT"
    mkdir -p "$T/entiers"
    for m in ara fra; do
      cp "$TD/$m.traineddata" "$T/entiers/" && "$CT" -c "$T/entiers/$m.traineddata" >/dev/null 2>&1 \
        || fatal "compactage de $m.traineddata"
      info "modèle $m compacté : $(wc -c < "$TD/$m.traineddata") -> $(wc -c < "$T/entiers/$m.traineddata") octets, sha256 $(sha256sum "$T/entiers/$m.traineddata" | cut -c1-16)…"
    done
    TD="$T/entiers" ;;
  *) fatal "GED_BANC_MODELES : precis ou entiers attendu" ;;
esac
(cd "$B" && mvn -o -B -q dependency:build-classpath -Dmdep.outputFile="$(natif "$T/cp.txt")" -Dmdep.includeScope=runtime) \
  || fatal "classpath du backend introuvable hors ligne"
CP="$(cat "$T/cp.txt")"
javac -encoding UTF-8 -nowarn -proc:none -implicit:class -d "$(natif "$T/classes")" -cp "$CP" \
  -sourcepath "$(natif "$B/src/main/java")${SEP}$(natif "$B/src/test/java")" \
  "$(natif "$RECETTE_RACINE/e10/BancOcrReduit.java")" || fatal "compilation du banc"
info "modèles : $MODELES"
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp "$(natif "$T/classes")${SEP}${CP}" com.ipt.ged.charge.BancOcrReduit "$TESS" "$(natif "$TD")" "$PAGES" "$LANGUE" "$DPI"
