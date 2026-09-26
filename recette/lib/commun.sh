#!/usr/bin/env bash
# Fonctions communes aux scripts de recette (bash 4+, Linux ou Git Bash sous Windows).
#
# Chaque script de recette produit des lignes au format stable
#     RESULTAT|<identifiant>|<OK|ECHEC|AVERT|NA>|<libellé>|<détail>
# pour pouvoir être relu par un humain ET agrégé par l'intégration continue
# ou le script de déploiement (grep '^RESULTAT|' | cut -d'|' -f3).
# Code de sortie : 0 si aucun ECHEC, 1 sinon, 2 si le script n'a pas pu s'exécuter.

set -o pipefail

RECETTE_RACINE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPOT_RACINE="$(cd "$RECETTE_RACINE/.." && pwd)"

NB_OK=0; NB_ECHEC=0; NB_AVERT=0; NB_NA=0

if [[ -t 1 ]]; then
  _VERT=$'\e[32m'; _ROUGE=$'\e[31m'; _JAUNE=$'\e[33m'; _GRIS=$'\e[90m'; _RAZ=$'\e[0m'
else
  _VERT=""; _ROUGE=""; _JAUNE=""; _GRIS=""; _RAZ=""
fi

# resultat <id> <OK|ECHEC|AVERT|NA> <libellé> [détail]
resultat() {
  local id="$1" statut="$2" libelle="$3" detail="${4:-}" couleur=""
  case "$statut" in
    OK) NB_OK=$((NB_OK + 1)); couleur="$_VERT" ;;
    ECHEC) NB_ECHEC=$((NB_ECHEC + 1)); couleur="$_ROUGE" ;;
    AVERT) NB_AVERT=$((NB_AVERT + 1)); couleur="$_JAUNE" ;;
    NA) NB_NA=$((NB_NA + 1)); couleur="$_GRIS" ;;
  esac
  printf '%sRESULTAT|%s|%s|%s|%s%s\n' "$couleur" "$id" "$statut" "$libelle" "$detail" "$_RAZ"
}

info() { printf '%s# %s%s\n' "$_GRIS" "$*" "$_RAZ"; }
fatal() { printf 'ERREUR_EXECUTION|%s\n' "$*" >&2; exit 2; }

bilan() {
  local titre="${1:-recette}"
  printf 'BILAN|%s|ok=%d|echec=%d|avert=%d|na=%d\n' "$titre" "$NB_OK" "$NB_ECHEC" "$NB_AVERT" "$NB_NA"
  [[ "$NB_ECHEC" -eq 0 ]]
}

# psql : celui du PATH, sinon l'installation Windows standard de PostgreSQL 16.
trouver_psql() {
  if [[ -n "${PSQL:-}" ]]; then return; fi
  if command -v psql >/dev/null 2>&1; then PSQL=psql
  elif [[ -x "/c/Program Files/PostgreSQL/16/bin/psql.exe" ]]; then PSQL="/c/Program Files/PostgreSQL/16/bin/psql.exe"
  else fatal "psql introuvable : définir PSQL=/chemin/vers/psql"; fi
  PG_BIN="$(dirname "$(command -v "$PSQL" 2>/dev/null || echo "$PSQL")")"
}

# Variables de connexion : standard libpq (PGHOST, PGPORT, PGUSER, PGDATABASE,
# PGPASSWORD ou ~/.pgpass). Aucune valeur secrète n'est écrite dans les scripts.
pg() { "$PSQL" -X -v ON_ERROR_STOP=1 -q "$@"; }

# Python 3 sans module tiers (les scripts d'analyse n'utilisent que la stdlib).
trouver_python() {
  if [[ -n "${PYTHON:-}" ]]; then return; fi
  for c in python3 python; do
    if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' 2>/dev/null; then
      PYTHON="$c"; return
    fi
  done
  fatal "Python 3.8+ introuvable : définir PYTHON=/chemin/vers/python3"
}

# Garde-fou : les scripts destructifs (création/suppression de base, rollback)
# refusent de viser la production.
refuser_production() {
  local cible="$1"
  if [[ "${GED_ENV:-}" == "prod" || "$cible" =~ (^|_)prod($|_) ]]; then
    fatal "Refus : ce script modifie la base « $cible » et ne doit jamais viser la production."
  fi
}
