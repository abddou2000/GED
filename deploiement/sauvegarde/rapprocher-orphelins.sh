#!/usr/bin/env bash
# =====================================================================
#  rapprocher-orphelins.sh — rapprochement base / référentiel de fichiers
#  après une restauration (DAT 6.5, « Cohérence entre la base et les fichiers »)
#
#  Usage : rapprocher-orphelins.sh --base NOM --racine DIR
#                                  [--appliquer] [--quarantaine DIR]
#                                  [--purger-apres-jours 7] [--rapport FICHIER]
#
#  - ORPHELIN : fichier présent sur le disque, inconnu de la base (déposé
#    après la sauvegarde de base restaurée). Inoffensif. Avec --appliquer, il
#    est déplacé en quarantaine ; il n'est supprimé qu'après 7 jours
#    (--purger-apres-jours), le temps de s'assurer qu'il n'est pas le fruit
#    d'une erreur de restauration.
#  - MANQUANT : fichier référencé par la base mais absent du disque. Le
#    document est signalé « à ré-importer » dans le rapport ; rien n'est
#    modifié en base.
#
#  Sans --appliquer, le script ne fait qu'établir le rapport.
#  Requête des fichiers référencés : $GED_SQL_FICHIERS_REFERENCES, par défaut
#  la table cle_fichier du lot stockage (un fichier = aa/bb/<id>.enc).
# =====================================================================
set -Eeuo pipefail
DIR_SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")/../scripts" && pwd)"
source "$DIR_SCRIPTS/commun.sh"

BASE=""; RACINE=""; APPLIQUER=non; QUARANTAINE=""; PURGE_JOURS=""; RAPPORT=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --base) BASE="$2"; shift 2 ;;
        --racine) RACINE="$2"; shift 2 ;;
        --appliquer) APPLIQUER=oui; shift ;;
        --quarantaine) QUARANTAINE="$2"; shift 2 ;;
        --purger-apres-jours) PURGE_JOURS="$2"; shift 2 ;;
        --rapport) RAPPORT="$2"; shift 2 ;;
        *) echec "option inconnue : $1" ;;
    esac
done
[[ -n "$BASE" && -n "$RACINE" ]] || echec "usage : --base NOM --racine DIR"
[[ -d "$RACINE" ]] || echec "référentiel introuvable : $RACINE"
exiger_commandes psql comm find sort
QUARANTAINE="${QUARANTAINE:-$(dirname "$RACINE")/quarantaine-orphelins}"
RAPPORT="${RAPPORT:-rapprochement-$(horodatage).txt}"
SQL="${GED_SQL_FICHIERS_REFERENCES:-select id::text from ${GED_SCHEMA:-ged}.cle_fichier}"

TRAVAIL="$(mktemp -d)"
trap 'rm -rf "$TRAVAIL"' EXIT

psql -X -d "$BASE" -At -v ON_ERROR_STOP=1 -c "$SQL" | tr -d '\r' | sort -u > "$TRAVAIL/base"
find "$RACINE" -type f -name '*.enc' -printf '%f\n' | sed 's/\.enc$//' | sort -u > "$TRAVAIL/disque"
comm -23 "$TRAVAIL/disque" "$TRAVAIL/base" > "$TRAVAIL/orphelins"
comm -13 "$TRAVAIL/disque" "$TRAVAIL/base" > "$TRAVAIL/manquants"

chemin_de() {   # même règle que le stockage : aa/bb/<id>.enc
    echo "${1:0:2}/${1:2:2}/$1.enc"
}

{
    echo "Rapprochement du $(date '+%Y-%m-%d %H:%M:%S')"
    echo "Base : $BASE — référentiel : $RACINE"
    echo "Fichiers référencés par la base : $(wc -l < "$TRAVAIL/base")"
    echo "Fichiers présents sur le disque : $(wc -l < "$TRAVAIL/disque")"
    echo "Orphelins (disque sans base)    : $(wc -l < "$TRAVAIL/orphelins")"
    echo "Manquants (base sans disque)    : $(wc -l < "$TRAVAIL/manquants")"
    echo
    echo "--- Documents à ré-importer (fichier manquant) ---"
    sed 's/^/A_REIMPORTER /' "$TRAVAIL/manquants"
    echo
    echo "--- Orphelins ---"
    while read -r id; do echo "ORPHELIN $(chemin_de "$id")"; done < "$TRAVAIL/orphelins"
} > "$RAPPORT"

if [[ "$APPLIQUER" == oui ]]; then
    lot="$QUARANTAINE/$(horodatage)"
    while read -r id; do
        rel="$(chemin_de "$id")"
        mkdir -p "$lot/$(dirname "$rel")"
        mv "$RACINE/$rel" "$lot/$rel"
    done < "$TRAVAIL/orphelins"
    [[ -s "$TRAVAIL/orphelins" ]] && journal "$(wc -l < "$TRAVAIL/orphelins") orphelin(s) mis en quarantaine dans $lot"
fi

if [[ -n "$PURGE_JOURS" && -d "$QUARANTAINE" ]]; then
    find "$QUARANTAINE" -mindepth 1 -maxdepth 1 -type d -mtime +"$PURGE_JOURS" -print -exec rm -rf {} + \
        | while read -r d; do journal "Quarantaine purgée : $d"; done
fi

journal "Rapport de rapprochement : $RAPPORT ($(wc -l < "$TRAVAIL/orphelins") orphelin(s), $(wc -l < "$TRAVAIL/manquants") manquant(s))"
