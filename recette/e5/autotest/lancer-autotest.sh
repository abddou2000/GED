#!/usr/bin/env bash
# Autotest de verifier-aucun-clair.sh sur des stockages SIMULÉS (aucune application requise).
#
# Des octets aléatoires précédés de l'en-tête GEDC sont, pour ce contrôle,
# indiscernables d'un vrai fichier AES-256-GCM : ils servent de stockage « conforme ».
# Chaque stockage « non conforme » contient un seul type de défaut, pour prouver que
# le contrôle correspondant — et lui seul — le détecte.

source "$(dirname "${BASH_SOURCE[0]}")/../../lib/commun.sh"
ICI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$ICI/../verifier-aucun-clair.sh"
DONNEES="$RECETTE_RACINE/donnees"
T="$(mktemp -d "${TMPDIR:-/tmp}/qa-e5-at.XXXXXX")"; trap 'rm -rf "$T"' EXIT

chiffre_simule() {  # chiffre_simule RACINE TAILLE [UUID] → chemin
  local u="${3:-$(uuid_aleatoire)}"; local d="$1/${u:0:2}/${u:2:2}"; mkdir -p "$d"
  { printf 'GEDC\001\000\020\000\000'; head -c "$2" /dev/urandom; } > "$d/$u.enc"; printf '%s' "$d/$u.enc"
}
statut_de() { grep "^RESULTAT|$2|" <<< "$1" | head -1 | cut -d'|' -f3; }
verifier() {  # verifier ID libellé SORTIE "E5-Sxx:STATUT …"
  local id="$1" lib="$2" sortie="$3" manques=() paire
  for paire in $4; do
    [[ "$(statut_de "$sortie" "${paire%%:*}")" == "${paire##*:}" ]] || manques+=("${paire%%:*} attendu ${paire##*:} obtenu $(statut_de "$sortie" "${paire%%:*}")")
  done
  [[ ${#manques[@]} -eq 0 ]] && resultat "$id" OK "$lib" || resultat "$id" ECHEC "$lib" "$(printf '%s ; ' "${manques[@]}")"
}

# 1. Conforme : tailles de 0 octet à 3 Mio, un temporaire récent toléré.
mkdir -p "$T/ok"
for n in 0 900 5000 70000 3145728; do chiffre_simule "$T/ok" "$n" >/dev/null; done
f="$(chiffre_simule "$T/ok" 2000)"; cp "$f" "$(dirname "$f")/.$(uuid_aleatoire).123.tmp"
S="$(bash "$SCRIPT" --racine "$T/ok" 2>&1)"
verifier AT-E5-01 "Stockage chiffré conforme : aucun écart" "$S" \
  "E5-S01:OK E5-S02:OK E5-S03:OK E5-S04:OK E5-S05:OK E5-S06:OK E5-S07:OK E5-S08:OK"

# 2. Fichiers du jeu de recette copiés EN CLAIR sous des noms conformes (.enc bien rangés).
mkdir -p "$T/clair"
for src in pdf_texte_fr_convention.pdf scan_fr_courrier.pdf document_fr.docx image_scan_fr.png note_texte_brut.txt; do
  u="$(uuid_aleatoire)"; mkdir -p "$T/clair/${u:0:2}/${u:2:2}"; cp "$DONNEES/$src" "$T/clair/${u:0:2}/${u:2:2}/$u.enc"
done
S="$(bash "$SCRIPT" --racine "$T/clair" 2>&1)"
verifier AT-E5-02 "Fichiers en clair déguisés en .enc : détectés" "$S" "E5-S01:OK E5-S02:ECHEC E5-S03:ECHEC E5-S04:ECHEC"
n="$(grep '^RESULTAT|E5-S03|' <<< "$S" | cut -d'|' -f5 | cut -d' ' -f1)"
[[ "$n" == 4 ]] && resultat AT-E5-03 OK "Signature de tête reconnue pour PDF, DOCX, PNG (4 fichiers binaires)" \
                || resultat AT-E5-03 ECHEC "Signature de tête reconnue pour 4 fichiers binaires" "obtenu : ${n:-0}"

# 3. Texte en clair précédé d'un faux en-tête GEDC : seules les chaînes et la compressibilité le trahissent.
mkdir -p "$T/faux-entete"; u="$(uuid_aleatoire)"; mkdir -p "$T/faux-entete/${u:0:2}/${u:2:2}"
{ printf 'GEDC\001\000\020\000\000'; for _ in $(seq 1 300); do printf 'Convention de partenariat zarkolinet Marchica ligne %s\n' "$RANDOM"; done; } > "$T/faux-entete/${u:0:2}/${u:2:2}/$u.enc"
S="$(bash "$SCRIPT" --racine "$T/faux-entete" 2>&1)"
verifier AT-E5-04 "Clair derrière un faux en-tête : détecté (chaînes, compressibilité)" "$S" "E5-S02:OK E5-S04:ECHEC E5-S05:ECHEC"

# 4. Arborescence non dérivée de l'identifiant, fichier étranger, temporaire ancien, racine vide.
mkdir -p "$T/arbo/zz/yy" "$T/arbo/ab/cd"
{ printf 'GEDC\001'; head -c 5000 /dev/urandom; } > "$T/arbo/zz/yy/$(uuid_aleatoire).enc"
{ printf 'GEDC\001'; head -c 5000 /dev/urandom; } > "$T/arbo/ab/cd/notes.bin"
f="$(chiffre_simule "$T/arbo" 3000)"; touch -d '3 hours ago' "$(dirname "$f")/.vieux.1.tmp"
S="$(bash "$SCRIPT" --racine "$T/arbo" 2>&1)"
verifier AT-E5-05 "Arborescence fausse, fichier étranger, temporaire abandonné : détectés" "$S" "E5-S01:ECHEC E5-S06:ECHEC E5-S07:AVERT"
mkdir -p "$T/vide"; S="$(bash "$SCRIPT" --racine "$T/vide" 2>&1)"
verifier AT-E5-06 "Racine vide : la preuve est refusée" "$S" "E5-S08:ECHEC"

# 5. Cache de prévisualisation : pas d'arborescence imposée, mais aucun clair.
mkdir -p "$T/cache"; cp "$DONNEES/pdf_texte_fr_facture.pdf" "$T/cache/apercu-1.bin"
S="$(bash "$SCRIPT" --racine "$T/ok" --racine-cache "$T/cache" 2>&1)"
verifier AT-E5-07 "Cache de prévisualisation en clair : détecté" "$S" "E5-S04:ECHEC E5-S06:OK"

bilan "autotest E5 aucun clair"
