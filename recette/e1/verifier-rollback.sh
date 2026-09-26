#!/usr/bin/env bash
# Recette E1 — retour arrière Liquibase (§4.2.2, critère de sortie « rollback testé en UAT »).
#
# Sur une base JETABLE déjà migrée (par verifier-base-vierge.sh, ou une copie de la base
# UAT restaurée pour l'occasion — jamais la base UAT elle-même, jamais la production) :
#   1. relevé du nombre N de changesets et du DDL complet ;
#   2. liquibase rollback des N changesets (le plus récent d'abord) : chacun doit
#      porter un retour arrière exécutable ;
#   3. contrôle : plus aucune table applicative, registre vidé ;
#   4. liquibase update à nouveau : le DDL doit être IDENTIQUE au relevé 1
#      (aller-retour sans perte ni dérive).
# Option --pas-a-pas : retour arrière changeset par changeset (rollbackCount 1, N fois)
# pour désigner précisément le premier changeset fautif.
#
# Usage : PGHOST=localhost PGUSER=postgres ./verifier-rollback.sh [--base NOM] [--backend CHEMIN] [--pas-a-pas]

source "$(dirname "${BASH_SOURCE[0]}")/../lib/commun.sh"
source "$RECETTE_RACINE/lib/liquibase.sh"

BASE=ged_qa_recette_e1; BACKEND="$DEPOT_RACINE/backend"; PAS_A_PAS=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) BASE="$2"; shift 2 ;;
    --backend) BACKEND="$(cd "$2" && pwd)"; shift 2 ;;
    --pas-a-pas) PAS_A_PAS=1; shift ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) fatal "option inconnue : $1" ;;
  esac
done
refuser_production "$BASE"
[[ "$BASE" == ged_qa* || "$BASE" == *recette* ]] || fatal "la base « $BASE » n'est pas une base jetable de recette"
trouver_psql
export PGHOST="${PGHOST:-localhost}" PGPORT="${PGPORT:-5432}" PGUSER="${PGUSER:-postgres}"
LB_URL="jdbc:postgresql://${PGHOST}:${PGPORT}/${BASE}"
TRAVAIL="$(mktemp -d "${TMPDIR:-/tmp}/qa-e1-rollback.XXXXXX")"
# Une requête de contrôle en erreur ne doit jamais passer pour « rien à signaler ».
sqlb() {
  local r
  r="$(PGDATABASE="$BASE" pg -At -c "$1")" || fatal "requête de contrôle en échec : $1"
  printf '%s' "${r//$'\r'/}"
}

N="$(sqlb "SELECT count(*) FROM $LB_SCHEMA_REGISTRE.databasechangelog" 2>/dev/null)"
[[ "$N" =~ ^[0-9]+$ && "$N" -gt 0 ]] || fatal "base $BASE non migrée (lancer d'abord verifier-base-vierge.sh)"
schema_normalise "$BASE" "$TRAVAIL/schema-avant.sql" "$LB_SCHEMA"
TABLES_AVANT="$(sqlb "SELECT count(*) FROM pg_tables WHERE schemaname = '$LB_SCHEMA'")"
info "$N changeset(s), $TABLES_AVANT table(s) dans $LB_SCHEMA"

if [[ "$PAS_A_PAS" -eq 1 ]]; then
  for i in $(seq 1 "$N"); do
    dernier="$(sqlb "SELECT filename || '::' || id FROM $LB_SCHEMA_REGISTRE.databasechangelog ORDER BY orderexecuted DESC LIMIT 1")"
    if ! liquibase_executer rollbackCount 1 > "$TRAVAIL/rollback-$i.log" 2>&1; then
      resultat E1-R01 ECHEC "Retour arrière de chaque changeset" "échec sur $dernier : $(grep -E 'ERROR|Exception' "$TRAVAIL/rollback-$i.log" | head -2 | tr '\n' ' ')"
      bilan "E1 rollback $BASE"; exit 1
    fi
  done
  resultat E1-R01 OK "Retour arrière de chaque changeset, un par un" "$N changeset(s)"
else
  if liquibase_executer rollbackCount "$N" > "$TRAVAIL/rollback.log" 2>&1; then
    resultat E1-R01 OK "Retour arrière des $N changesets"
  else
    resultat E1-R01 ECHEC "Retour arrière des $N changesets" "$(grep -E 'ERROR|Exception|Caused' "$TRAVAIL/rollback.log" | head -3 | tr '\n' ' ') — relancer avec --pas-a-pas pour isoler le changeset"
    bilan "E1 rollback $BASE"; exit 1
  fi
fi

reste_tables="$(sqlb "SELECT string_agg(tablename, ', ') FROM pg_tables WHERE schemaname = '$LB_SCHEMA'")"
reste_autres="$(sqlb "SELECT string_agg(c.relname || '(' || c.relkind::text || ')', ', ') FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = '$LB_SCHEMA' AND c.relkind IN ('S','v','m')")"
reste_types="$(sqlb "SELECT string_agg(t.typname, ', ') FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace WHERE n.nspname = '$LB_SCHEMA' AND t.typtype IN ('e','d','c') AND NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.reltype = t.oid)")"
reste_fonctions="$(sqlb "SELECT string_agg(p.proname, ', ') FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = '$LB_SCHEMA'")"
residu="$(printf '%s' "${reste_tables:+tables: $reste_tables ; }${reste_autres:+séquences/vues: $reste_autres ; }${reste_types:+types: $reste_types ; }${reste_fonctions:+fonctions: $reste_fonctions}")"
[[ -z "$residu" ]] && resultat E1-R02 OK "Schéma $LB_SCHEMA entièrement vidé par le retour arrière" \
                   || resultat E1-R02 ECHEC "Schéma $LB_SCHEMA entièrement vidé par le retour arrière" "résidus : $residu"
n_reg="$(sqlb "SELECT count(*) FROM $LB_SCHEMA_REGISTRE.databasechangelog")"
[[ "$n_reg" == 0 ]] && resultat E1-R03 OK "Registre Liquibase vidé" \
                    || resultat E1-R03 ECHEC "Registre Liquibase vidé" "$n_reg ligne(s) restantes"

if liquibase_executer update > "$TRAVAIL/update2.log" 2>&1; then
  schema_normalise "$BASE" "$TRAVAIL/schema-apres.sql" "$LB_SCHEMA"
  if diff -q "$TRAVAIL/schema-avant.sql" "$TRAVAIL/schema-apres.sql" >/dev/null; then
    resultat E1-R04 OK "Aller-retour update → rollback → update : DDL identique"
  else
    resultat E1-R04 ECHEC "Aller-retour update → rollback → update : DDL identique" "$(diff "$TRAVAIL/schema-avant.sql" "$TRAVAIL/schema-apres.sql" | head -6 | tr '\n' ' ')"
  fi
else
  resultat E1-R04 ECHEC "Réapplication du changelog après retour arrière" "$(grep -E 'ERROR|Exception' "$TRAVAIL/update2.log" | head -3 | tr '\n' ' ')"
fi

info "traces conservées dans $TRAVAIL"
bilan "E1 rollback $BASE"
