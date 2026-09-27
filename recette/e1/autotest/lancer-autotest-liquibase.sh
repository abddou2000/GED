#!/usr/bin/env bash
# Autotest de verifier-rollback.sh : il doit DÉTECTER un changeset sans retour arrière
# (E1-R01) et un faux retour arrière qui ne défait rien (E1-R02, E1-R04).
#
# Le classpath Liquibase vient du backend passé en argument (il doit embarquer
# liquibase-core et le pilote PostgreSQL, c'est-à-dire le backend après E1) ; les
# changelogs viennent des fixtures du dossier liquibase/.
# Usage : PGHOST=localhost PGUSER=postgres ./lancer-autotest-liquibase.sh [CHEMIN_BACKEND]

source "$(dirname "${BASH_SOURCE[0]}")/../../lib/commun.sh"
source "$RECETTE_RACINE/lib/liquibase.sh"
ICI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND="$(cd "${1:-$DEPOT_RACINE/backend}" && pwd)"
BASE=ged_qa_recette_e1_at
trouver_psql
export PGHOST="${PGHOST:-localhost}" PGPORT="${PGPORT:-5432}" PGUSER="${PGUSER:-postgres}"
export LB_URL="jdbc:postgresql://${PGHOST}:${PGPORT}/${BASE}"
statut_de() { grep "^RESULTAT|$2|" <<< "$1" | head -1 | cut -d'|' -f3; }

preparer() {  # base neuve + changelog de fixture appliqué
  PGDATABASE=postgres pg -c "DROP DATABASE IF EXISTS \"$BASE\" WITH (FORCE)" >/dev/null 2>&1
  PGDATABASE=postgres pg -v base="$BASE" -f "$BACKEND/scripts/db/preparer-base.sql" >/dev/null 2>&1 || fatal "preparer-base.sql"
  LB_RESSOURCES="$ICI/liquibase/$1" liquibase_executer update >/dev/null 2>&1 || fatal "update de la fixture $1"
}

preparer sans-rollback
S1="$(LB_RESSOURCES="$ICI/liquibase/sans-rollback" bash "$ICI/../verifier-rollback.sh" --base "$BASE" --backend "$BACKEND" 2>&1)"
[[ "$(statut_de "$S1" E1-R01)" == ECHEC ]] \
  && resultat AT-L1 OK "Changeset SQL sans rollback détecté (E1-R01)" \
  || resultat AT-L1 ECHEC "Changeset SQL sans rollback détecté (E1-R01)" "$(grep RESULTAT <<< "$S1" | head -2)"

preparer faux-rollback
S2="$(LB_RESSOURCES="$ICI/liquibase/faux-rollback" bash "$ICI/../verifier-rollback.sh" --base "$BASE" --backend "$BACKEND" 2>&1)"
[[ "$(statut_de "$S2" E1-R02)" == ECHEC && "$(statut_de "$S2" E1-R04)" == ECHEC ]] \
  && resultat AT-L2 OK "Faux rollback démasqué (résidu E1-R02, aller-retour E1-R04)" \
  || resultat AT-L2 ECHEC "Faux rollback démasqué" "$(grep RESULTAT <<< "$S2" | cut -d'|' -f2,3 | tr '\n' ' ')"

PGDATABASE=postgres pg -c "DROP DATABASE IF EXISTS \"$BASE\" WITH (FORCE)" >/dev/null 2>&1
bilan "autotest rollback Liquibase"
