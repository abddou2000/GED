#!/usr/bin/env bash
# Recette E5 — « Aucun fichier en clair sur le disque » (critère de sortie E5 ; DAT §6.1.1, §6.1.2).
#
# Parcourt la racine du stockage et contrôle CHAQUE fichier (ou un échantillon) :
#   S01 arborescence /<racine>/aa/bb/<uuid>.enc, aa et bb dérivés de l'identifiant ;
#   S02 en-tête du format chiffré (défaut : « GEDC » + version 1, format de dev3) ;
#   S03 aucune signature de format en clair en tête de fichier (PDF, PNG, ZIP/OOXML, JPEG,
#       TIFF, GIF, OLE, RTF, exécutable, XML, BOM) ;
#   S04 aucune chaîne en clair connue n'importe où dans le fichier (%PDF-, endobj,
#       [Content_Types].xml, mots-témoins des jeux de recette, chaîne EICAR…) ;
#   S05 contenu incompressible (gzip ne gagne rien sur un chiffré ; un texte, un PDF ou un
#       DOCX non chiffré se compresse) — petits fichiers : proportion d'octets imprimables ;
#   S06 aucun fichier étranger au format ; S07 aucun temporaire abandonné (.tmp) ancien ;
#   S08 au moins un fichier contrôlé (une racine vide ne prouve rien).
#
# Usage : verifier-aucun-clair.sh --racine DOSSIER [--racine-cache DOSSIER] [--entete GEDC|aucune]
#                                 [--echantillon N] [--age-temporaire MINUTES]
#   --racine-cache   cache de prévisualisation chiffré (§6.1.6) : contrôlé sans l'arborescence
# Lecture seule. Dépendances : bash, find, od, grep, gzip, head, wc (coreutils).

source "$(dirname "${BASH_SOURCE[0]}")/../lib/commun.sh"

RACINES=(); CACHES=(); ENTETE=GEDC; ECHANTILLON=0; AGE_TMP=60
while [[ $# -gt 0 ]]; do
  case "$1" in
    --racine) RACINES+=("$2"); shift 2 ;;
    --racine-cache) CACHES+=("$2"); shift 2 ;;
    --entete) ENTETE="$2"; shift 2 ;;
    --echantillon) ECHANTILLON="$2"; shift 2 ;;
    --age-temporaire) AGE_TMP="$2"; shift 2 ;;
    -h|--help) sed -n '2,21p' "$0"; exit 0 ;;
    *) fatal "option inconnue : $1" ;;
  esac
done
[[ ${#RACINES[@]} -gt 0 ]] || fatal "--racine obligatoire"
for r in "${RACINES[@]}" "${CACHES[@]}"; do [[ -d "$r" ]] || fatal "dossier introuvable : $r"; done
TRAVAIL="$(mktemp -d "${TMPDIR:-/tmp}/qa-e5-clair.XXXXXX")"; trap 'rm -rf "$TRAVAIL"' EXIT
export LC_ALL=C

# Chaînes en clair recherchées PARTOUT dans le fichier. Longueur ≥ 5 octets : sur 200 Mo
# de données chiffrées, une coïncidence aléatoire est de l'ordre de 10⁻⁴ au pire.
{
  printf '%s\n' '%PDF-' '%%EOF' 'endobj' 'endstream' '/Type /Page' '[Content_Types].xml' 'word/document.xml' \
    'docProps/' 'xl/workbook' 'mimetypeapplication/vnd.oasis' '<?xml ' '<html' 'Exif' 'JFIF' 'IHDR' \
    'zarkolinet' 'Marchica' 'Nador' 'convention de partenariat' "$(printf 'EICAR-STANDARD-')ANTIVIRUS" \
    "$(printf '\xd8\xb2\xd8\xb1\xd9\x83\xd9\x88\xd9\x84\xd9\x8a\xd9\x86')" "$(printf '\xd9\x85\xd8\xa7\xd8\xb1\xd8\xaa\xd8\xb4\xd9\x8a\xd9\x83\xd8\xa7')"
  [[ -n "${GED_TEMOINS_SUPPLEMENTAIRES:-}" ]] && tr ',' '\n' <<< "$GED_TEMOINS_SUPPLEMENTAIRES"
} | awk 'length($0) >= 4' > "$TRAVAIL/chaines"
# Les chaînes de 4 octets (Exif, JFIF, IHDR) ne sont cherchées que dans les 64 premiers Kio.
grep -E '^.{5,}$' "$TRAVAIL/chaines" > "$TRAVAIL/chaines-partout"
grep -E '^.{4}$' "$TRAVAIL/chaines" > "$TRAVAIL/chaines-tete"

# Signatures de tête (hexadécimal des premiers octets).
MAGIQUES='25504446:PDF 89504e47:PNG 504b0304:ZIP/OOXML/ODF ffd8ff:JPEG 49492a00:TIFF 4d4d002a:TIFF 47494638:GIF
d0cf11e0:OLE(doc/xls) 7b5c727466:RTF 4d5a:exécutable 3c3f786d6c:XML 3c68746d6c:HTML efbbbf:texte-UTF8-BOM 1f8b:GZIP'

declare -A NB=() EX=()
constat() {  # constat CONTROLE message
  NB[$1]=$(( ${NB[$1]:-0} + 1 ))
  (( ${NB[$1]} <= 5 )) && EX[$1]+="$2 ; "
}

controler() {  # controler FICHIER RACINE AVEC_ARBORESCENCE
  local f="$1" racine="$2" arbo="$3" rel nom taille tete
  rel="${f#"$racine"/}"; nom="$(basename "$f")"
  # Temporaire d'écriture atomique (.<uuid>.<aléa>.tmp) : toléré s'il est récent.
  if [[ "$nom" == .*.tmp ]]; then
    if [[ -n "$(find "$f" -mmin +"$AGE_TMP" 2>/dev/null)" ]]; then constat S07 "$rel (plus de $AGE_TMP min)"; fi
    return
  fi
  CONTROLES=$((CONTROLES + 1))
  if [[ "$arbo" == 1 ]]; then
    if [[ "$rel" =~ ^([0-9a-f]{2})/([0-9a-f]{2})/(([0-9a-f]{8})-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.enc$ ]]; then
      local uuid="${BASH_REMATCH[3]}"
      [[ "${uuid:0:2}" == "${BASH_REMATCH[1]}" && "${uuid:2:2}" == "${BASH_REMATCH[2]}" ]] \
        || constat S01 "$rel (aa/bb ne dérivent pas de l'identifiant)"
    elif [[ "$nom" == *.enc ]]; then constat S01 "$rel"
    else constat S06 "$rel"; fi
  fi
  taille=$(wc -c < "$f")
  tete="$(od -An -tx1 -N8 "$f" | tr -d ' \n')"
  if [[ "$ENTETE" != aucune ]]; then
    local attendu; attendu="$(printf '%s' "$ENTETE" | od -An -tx1 | tr -d ' \n')01"
    [[ "$tete" == "$attendu"* ]] || constat S02 "$rel (tête ${tete:-vide})"
  fi
  local m
  for m in $MAGIQUES; do
    [[ -n "$tete" && "$tete" == "${m%%:*}"* ]] && { constat S03 "$rel : ${m#*:}"; break; }
  done
  local trouve
  trouve="$(grep -a -o -F -f "$TRAVAIL/chaines-partout" "$f" 2>/dev/null | sort -u | head -3 | tr '\n' ',')"
  [[ -z "$trouve" ]] && trouve="$(head -c 65536 "$f" | grep -a -o -F -f "$TRAVAIL/chaines-tete" 2>/dev/null | sort -u | head -3 | tr '\n' ',')"
  [[ -n "$trouve" ]] && constat S04 "$rel : « ${trouve%,} »"
  if (( taille >= 4096 )); then
    # Échantillon de 1 Mio : gzip -1 suffit à distinguer un chiffré (ratio ≈ 1,0003)
    # d'un contenu structuré ; les formats déjà compressés (JPEG, ZIP) sont pris en S03.
    local n c
    n=$(head -c 1048576 "$f" | wc -c); c=$(head -c 1048576 "$f" | gzip -1 -c | wc -c)
    (( c * 100 < n * 98 )) && constat S05 "$rel (compressible à $(( c * 100 / n )) %)"
  elif (( taille > 0 )); then
    local imp; imp=$(tr -cd '\11\12\15\40-\176' < "$f" | wc -c)
    (( imp * 100 > taille * 85 )) && constat S05 "$rel (${imp}/${taille} octets imprimables)"
  fi
}

CONTROLES=0
lister() { find "$1" -type f 2>/dev/null | { if (( ECHANTILLON > 0 )); then shuf -n "$ECHANTILLON"; else cat; fi; }; }
for r in "${RACINES[@]}"; do
  r="${r%/}"
  while IFS= read -r f; do controler "$f" "$r" 1; done < <(lister "$r")
done
for r in "${CACHES[@]}"; do
  r="${r%/}"
  while IFS= read -r f; do controler "$f" "$r" 0; done < <(lister "$r")
done
info "$CONTROLES fichier(s) contrôlé(s) sous ${RACINES[*]} ${CACHES[*]}"

rapport() {  # rapport ID libellé [gravité si constat]
  local id="$1" lib="$2" grav="${3:-ECHEC}"
  if (( ${NB[$id]:-0} > 0 )); then resultat "E5-$id" "$grav" "$lib" "${NB[$id]} fichier(s) : ${EX[$id]%; }"
  else resultat "E5-$id" OK "$lib"; fi
}
rapport S01 "Arborescence aa/bb/<uuid>.enc dérivée de l'identifiant [6.1.1]"
if [[ "$ENTETE" == aucune ]]; then resultat E5-S02 NA "En-tête du format chiffré" "--entete aucune"
else rapport S02 "En-tête du format chiffré « $ENTETE » v1 [6.1.2]"; fi
rapport S03 "Aucune signature de format en clair en tête de fichier [6.1.2]"
rapport S04 "Aucune chaîne en clair connue dans les fichiers stockés [6.1.2]"
rapport S05 "Contenu incompressible (chiffré) [6.1.2]"
rapport S06 "Aucun fichier étranger au format du stockage [6.1.1]"
rapport S07 "Aucun temporaire d'écriture abandonné [6.1.1]" AVERT
(( CONTROLES > 0 )) && resultat E5-S08 OK "Fichiers effectivement contrôlés" "$CONTROLES" \
                    || resultat E5-S08 ECHEC "Fichiers effectivement contrôlés" "aucun fichier : la preuve est vide"
bilan "E5 aucun fichier en clair"
