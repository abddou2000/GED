#!/usr/bin/env bash
# Exécution de Liquibase pour la recette, indépendamment du code applicatif.
#
# Mode par défaut (java) : LiquibaseRecette.java lancé avec le classpath d'exécution
# du backend, résolu HORS LIGNE par Maven (mvn -o dependency:build-classpath) : la
# recette utilise exactement le liquibase-core et le pilote livrés, sans rien
# télécharger. Sur un serveur UAT sans Maven, définir GED_LIQUIBASE_CMD vers la CLI
# Liquibase officielle ; la commande reçoit le but et les options --url, --username…
#
# Variables : BACKEND (dossier du pom), LB_URL, LB_USER, LB_PASSWORD, LB_SCHEMA,
#             LB_SCHEMA_REGISTRE, LB_CHANGELOG, LB_RESSOURCES (dossier des changelogs si
#             différent de backend/src/main/resources, pour les autotests).

export LB_CHANGELOG="${LB_CHANGELOG:-db/changelog/db.changelog-master.xml}"
export LB_SCHEMA="${LB_SCHEMA:-ged}"
export LB_SCHEMA_REGISTRE="${LB_SCHEMA_REGISTRE:-ged_liquibase}"
export LB_USER="${LB_USER:-ged_owner}"

_lb_classpath() {
  if [[ -z "${_LB_CP:-}" ]]; then
    local f; f="$(mktemp "${TMPDIR:-/tmp}/qa-cp.XXXXXX")"
    (cd "$BACKEND" && mvn -o -B -q dependency:build-classpath -Dmdep.outputFile="$f" -Dmdep.includeScope=runtime) >&2 \
      || { echo "classpath du backend introuvable hors ligne (mvn -o dependency:build-classpath)" >&2; return 1; }
    _LB_CP="$(cat "$f")"
  fi
}

# liquibase_executer <update|rollbackCount N|status>
liquibase_executer() {
  local but="$1"; shift
  if [[ -n "${GED_LIQUIBASE_CMD:-}" ]]; then
    local cli_but="$but"; local extra=()
    [[ "$but" == rollbackCount ]] && { cli_but="rollback-count"; extra=(--count="$1"); }
    # shellcheck disable=SC2086
    $GED_LIQUIBASE_CMD "$cli_but" --url="$LB_URL" --username="$LB_USER" --password="${LB_PASSWORD:-}" \
      --changelog-file="$LB_CHANGELOG" --default-schema-name="$LB_SCHEMA" \
      --liquibase-schema-name="$LB_SCHEMA_REGISTRE" "${extra[@]}"
    return
  fi
  _lb_classpath || return 1
  LB_URL="$LB_URL" LB_SEARCH_PATH="${LB_RESSOURCES:-$BACKEND/src/main/resources}" \
    java -cp "$_LB_CP" "$RECETTE_RACINE/lib/LiquibaseRecette.java" "$but" "$@"
}

# Empreinte normalisée du schéma : DDL seul, sans commentaires ni SET, pour comparer
# deux états (après update, après rollback puis update, après démarrage de l'appli).
schema_normalise() {  # $1 base, $2 fichier de sortie, schémas suivants
  local base="$1" sortie="$2"; shift 2
  local opts=(); for s in "$@"; do opts+=(-n "$s"); done
  "$PG_BIN/pg_dump" -s --no-owner --no-privileges "${opts[@]}" -d "$base" \
    | tr -d '\r' | grep -v -E '^(--|SET |SELECT pg_catalog\.set_config|\\restrict|\\unrestrict)' | sed '/^$/d' > "$sortie"
}
