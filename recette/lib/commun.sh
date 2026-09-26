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

# Programmes Java de la recette lancés en mode « fichier source » (JDK 17) : aucun Python
# (décision D5 de la revue technique). UTF-8 imposé : sous Windows, le JDK 17 lirait le
# source en cp1252 et mutilerait les libellés accentués.
java_source() {
  command -v java >/dev/null 2>&1 || fatal "java (JDK 17) introuvable"
  java -Dfile.encoding=UTF-8 "$@"
}

# Garde-fou : les scripts destructifs (création/suppression de base, rollback)
# refusent de viser la production.
refuser_production() {
  local cible="$1"
  if [[ "${GED_ENV:-}" == "prod" || "$cible" =~ (^|_)prod($|_) ]]; then
    fatal "Refus : ce script modifie la base « $cible » et ne doit jamais viser la production."
  fi
}

# UUID v4 aléatoire sans dépendance (ni /proc, absent sous Windows, ni uuidgen, ni Python).
uuid_aleatoire() {
  local h; h="$(od -An -tx1 -N16 /dev/urandom | tr -d ' \n')"
  printf '%s-%s-4%s-%x%s-%s\n' "${h:0:8}" "${h:8:4}" "${h:13:3}" $(( (0x${h:16:1} & 3) | 8 )) "${h:17:3}" "${h:20:12}"
}
