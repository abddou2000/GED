#!/usr/bin/env bash
# =====================================================================
#  Fonctions communes aux scripts d'exploitation de la GED (déploiement,
#  sauvegarde, restauration). À sourcer, jamais à exécuter.
# =====================================================================

# Journal horodaté, sur la sortie standard ET dans le fichier $GED_JOURNAL
# s'il est défini et inscriptible : chaque opération d'exploitation laisse
# une trace consultable après coup.
journal() {
    local ligne
    ligne="$(date '+%Y-%m-%d %H:%M:%S') [$(basename "$0")] $*"
    echo "$ligne"
    if [[ -n "${GED_JOURNAL:-}" ]]; then
        { echo "$ligne" >> "$GED_JOURNAL"; } 2>/dev/null || true
    fi
}

echec() {
    journal "ÉCHEC : $*"
    exit 1
}

# Charge un fichier de variables (format KEY=valeur) en les exportant.
# Refuse un fichier lisible par d'autres que son propriétaire : il contient
# des secrets (DAT 10.1, permissions 0400).
charger_env() {
    local fichier="$1" obligatoire="${2:-oui}"
    if [[ ! -f "$fichier" ]]; then
        [[ "$obligatoire" == "oui" ]] && echec "fichier de configuration absent : $fichier"
        return 0
    fi
    if [[ "${GED_IGNORER_PERMISSIONS:-non}" != "oui" ]]; then
        local droits
        droits="$(stat -c '%a' "$fichier" 2>/dev/null || echo inconnu)"
        case "$droits" in
            400|600) ;;
            *) echec "$fichier doit être en 0400 (actuellement $droits) : il contient des secrets" ;;
        esac
    fi
    set -a
    # shellcheck disable=SC1090
    source "$fichier"
    set +a
}

exiger_commandes() {
    local manquantes=()
    for c in "$@"; do
        command -v "$c" >/dev/null 2>&1 || manquantes+=("$c")
    done
    (( ${#manquantes[@]} == 0 )) || echec "commandes absentes : ${manquantes[*]}"
}

horodatage() {
    date '+%Y%m%d-%H%M%S'
}
