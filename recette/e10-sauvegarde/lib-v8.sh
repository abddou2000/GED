#!/usr/bin/env bash
# =====================================================================
#  Fonctions communes de la recette e10-sauvegarde (vague 8).
#  À sourcer. Aucune base hors du préfixe ged_qa_v8_ n'est jamais visée.
# =====================================================================
set -o pipefail

V8_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/commun.sh
source "$V8_DIR/../lib/commun.sh"
trouver_psql
export PATH="$PG_BIN:$PATH"
DEPOT="$DEPOT_RACINE"
SAUV="$DEPOT/deploiement/sauvegarde"
SCRIPTS="$DEPOT/deploiement/scripts"
: "${V8_TRAVAIL:?V8_TRAVAIL (répertoire de travail hors dépôt) obligatoire}"
mkdir -p "$V8_TRAVAIL"
export PGHOST="${PGHOST:-localhost}" PGPORT="${PGPORT:-5432}" PGUSER="${PGUSER:-postgres}"
export PGOPTIONS="-c client_min_messages=warning"

# Garde-fou : seul le préfixe ged_qa_v8_ est autorisé.
exiger_base_v8() {
  [[ "$1" =~ ^ged_qa_v8_[a-z0-9_]+$ ]] || fatal "base refusée (préfixe ged_qa_v8_ obligatoire) : $1"
}

# Requête sur une base v8 (sortie brute, sans \r).
q() { exiger_base_v8 "$1"; "$PSQL" -X -q -v ON_ERROR_STOP=1 -At -d "$1" -c "$2" | tr -d '\r'; }

# Existence d'une base (lecture du catalogue, sur la base postgres, pour CREATE/DROP seulement).
base_existe() { exiger_base_v8 "$1"; [[ "$("$PSQL" -X -At -d postgres -c "select count(*) from pg_database where datname = '$1'" | tr -d '\r')" == 1 ]]; }
supprimer_base() { exiger_base_v8 "$1"; "$PG_BIN/dropdb" --if-exists "$1"; }

# Base préparée (preparer-base.sql) puis migrée par Liquibase (outil de recette).
preparer_et_migrer() {
  local base="$1"; exiger_base_v8 "$base"
  "$PSQL" -X -q -U postgres -d postgres -v base="$base" -f "$DEPOT/backend/scripts/db/preparer-base.sql" >/dev/null
  # shellcheck source=../lib/liquibase.sh
  source "$DEPOT/recette/lib/liquibase.sh"
  BACKEND="$DEPOT/backend" TMPDIR="$V8_TRAVAIL" LB_URL="jdbc:postgresql://${PGHOST}:${PGPORT}/$base" \
    liquibase_executer update > "$V8_TRAVAIL/liquibase-$base.log" 2>&1 \
    || fatal "migration Liquibase de $base en échec (voir $V8_TRAVAIL/liquibase-$base.log)"
}

# Empreinte de chaque table d'un schéma : « table|lignes|md5 du contenu ordonné ».
empreintes_tables() {  # base schéma
  local base="$1" schema="$2" t
  for t in $(q "$base" "select tablename from pg_tables where schemaname = '$schema' order by 1"); do
    echo "$t|$(q "$base" "select count(*) || '|' || coalesce(md5(string_agg(x::text, '#' order by x::text)), '-') from $schema.\"$t\" x")"
  done
}

chemin_fichier() { echo "${1:0:2}/${1:2:2}/$1.enc"; }
