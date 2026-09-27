#!/usr/bin/env bash
# =====================================================================
#  Fonctions communes aux scripts de sauvegarde et de restauration.
#  À sourcer. Charge /etc/ged/sauvegarde.env (ou $GED_SAUVEGARDE_ENV).
# =====================================================================
DIR_SAUVEGARDE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../scripts/commun.sh
source "$DIR_SAUVEGARDE/../scripts/commun.sh"

charger_env "${GED_SAUVEGARDE_ENV:-${GED_CONF_DIR:-/etc/ged}/sauvegarde.env}"

: "${GED_SAUVEGARDE_DESTINATION:?GED_SAUVEGARDE_DESTINATION absente}"
DEST_BASE="$GED_SAUVEGARDE_DESTINATION/base"
DEST_FICHIERS="$GED_SAUVEGARDE_DESTINATION/fichiers"
DEST_CLES="${GED_SAUVEGARDE_CLES_DESTINATION:-}"
RETENTION_JOURS="${GED_SAUVEGARDE_RETENTION_JOURS:-30}"
RETENTION_MOIS="${GED_SAUVEGARDE_RETENTION_MOIS:-12}"

# Chaque sauvegarde est un répertoire horodaté qui ne reçoit son marqueur
# TERMINE qu'une fois complète et vérifiée : une sauvegarde interrompue n'est
# jamais prise pour une sauvegarde valide.
# Avec « inventaire », on liste les fichiers (chemin, taille) au lieu de les
# hacher : le référentiel pèse jusqu'à 2 To (DAT 6.6), et chaque fichier
# chiffré porte déjà son contrôle d'intégrité (GCM, empreinte en base).
marquer_termine() {
    local dir="$1" mode="${2:-empreintes}"
    if [[ "$mode" == inventaire ]]; then
        (cd "$dir" && find . -type f ! -name TERMINE ! -name INVENTAIRE -printf '%P %s\n' | sort > INVENTAIRE)
    else
        (cd "$dir" && find . -type f ! -name TERMINE ! -name EMPREINTES -print0 | sort -z \
            | xargs -0 -r sha256sum > EMPREINTES)
    fi
    date '+%Y-%m-%dT%H:%M:%S%z' > "$dir/TERMINE"
}

# Dernière sauvegarde complète d'un type (base, fichiers), ou rien.
derniere_terminee() {
    local racine="$1"
    [[ -d "$racine" ]] || return 0
    local d
    for d in $(ls -1 "$racine" 2>/dev/null | sort -r); do
        [[ -f "$racine/$d/TERMINE" ]] && { echo "$racine/$d"; return 0; }
    done
}

# Rétention DAT 6.5 : on garde tout ce qui a moins de RETENTION_JOURS jours,
# et au-delà la première sauvegarde de chaque mois pendant RETENTION_MOIS mois.
appliquer_retention() {
    local racine="$1"
    [[ -d "$racine" ]] || return 0
    local limite_jours limite_mois nom date_sauv mois deja=""
    limite_jours="$(date -d "-$RETENTION_JOURS days" '+%Y%m%d')"
    limite_mois="$(date -d "-$RETENTION_MOIS months" '+%Y%m%d')"
    for nom in $(ls -1 "$racine" | sort); do
        date_sauv="${nom:0:8}"
        [[ "$date_sauv" =~ ^[0-9]{8}$ ]] || continue
        mois="${date_sauv:0:6}"
        if [[ "$date_sauv" < "$limite_jours" ]]; then
            if [[ "$date_sauv" > "$limite_mois" && " $deja " != *" $mois "* && -f "$racine/$nom/TERMINE" ]]; then
                deja="$deja $mois"          # première sauvegarde complète du mois : gardée
                continue
            fi
            journal "Rétention : suppression de $racine/$nom"
            rm -rf "${racine:?}/$nom"
        elif [[ -f "$racine/$nom/TERMINE" ]]; then
            deja="$deja $mois"
        fi
    done
}
