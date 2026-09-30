#!/usr/bin/env bash
# =====================================================================
#  Restauration à blanc des droits de niveau base (DAT 6.5, §4.2.3 ;
#  ANO-E10-006) sur une instance PostgreSQL jetable :
#    1. rôles et base préparés par les VRAIS scripts (creer-roles.sql si
#       présent, sinon rôles minimaux ; preparer-base.sql), une table peuplée ;
#    2. sauvegarder-base.sh --logique ;
#    3. restaurer.sh base-logique vers une base NEUVE de nom différent ;
#    4. contrôles : pg_database.datacl identique (rien pour PUBLIC),
#       propriétaire identique, réglages ALTER ROLE … IN DATABASE identiques,
#       données présentes, CONNECT refusé à un rôle hors des trois.
#  Usage : test-restaurer-droits.sh   (code 0 si tout est conforme)
# =====================================================================
set -Eeuo pipefail
ICI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$ICI/instance-jetable.sh"
SAUV="$(cd "$ICI/.." && pwd)"
DEPOT="$(cd "$SAUV/../.." && pwd)"
RESTAURER="${RESTAURER:-$SAUV/restaurer.sh}"   # surchargeable : rejouer le test sur une autre version

instance_demarrer
trap 'instance_arreter' EXIT
W="$JETABLE/travail"; mkdir -p "$W/sauvegardes"
export GED_JOURNAL="$W/journal.log"

q() { psql -X -q -v ON_ERROR_STOP=1 -At -d "$1" -c "$2" | tr -d '\r'; }
acl() { q postgres "select datacl::text from pg_database where datname = '$1'"; }
proprietaire() { q postgres "select pg_get_userbyid(datdba) from pg_database where datname = '$1'"; }
reglages() { q postgres "select coalesce(r.rolname, '*') || ' ' || array_to_string(s.setconfig, ',')
                         from pg_db_role_setting s join pg_database d on d.oid = s.setdatabase
                         left join pg_roles r on r.oid = s.setrole where d.datname = '$1' order by 1"; }

for r in ged_owner ged_app ged_readonly; do q postgres "create role $r login"; done
q postgres "create role intrus login"
psql -X -q -v ON_ERROR_STOP=1 -d postgres -v base=ged_essai -f "$DEPOT/backend/scripts/db/preparer-base.sql" >/dev/null
q ged_essai "set role ged_owner; create table ged.document(id int primary key, nom text); insert into ged.document select g, 'doc-' || g from generate_series(1, 25) g"
q postgres "alter role ged_app in database ged_essai set statement_timeout = '30s'"   # réglage non liste

REF_ACL="$(acl ged_essai)"; REF_PROP="$(proprietaire ged_essai)"; REF_REGL="$(reglages ged_essai)"

cat > "$W/sauvegarde.env" <<ENV
PGDATABASE=ged_essai
GED_SAUVEGARDE_DESTINATION=$W/sauvegardes
ENV
export GED_SAUVEGARDE_ENV="$W/sauvegarde.env" GED_IGNORER_PERMISSIONS=oui
"$SAUV/sauvegarder-base.sh" --logique >/dev/null
SAUVEGARDE="$(ls -d "$W"/sauvegardes/base/*/ | head -1)"; SAUVEGARDE="${SAUVEGARDE%/}"

"$RESTAURER" base-logique --sauvegarde "$SAUVEGARDE" --cible ged_restauree > "$W/restaurer.log" 2>&1 \
    || { cat "$W/restaurer.log"; ko "restaurer.sh base-logique"; }

echo "Droits de niveau base après restauration logique"
verifier "datacl identique ($REF_ACL)" "[[ '$(acl ged_restauree)' == '$REF_ACL' ]]"
verifier "rien pour PUBLIC" "[[ '$(acl ged_restauree)' != '' && '$(acl ged_restauree)' != *'{='* && '$(acl ged_restauree)' != *',='* ]]"
verifier "propriétaire identique ($REF_PROP)" "[[ '$(proprietaire ged_restauree)' == '$REF_PROP' ]]"
verifier "réglages des rôles identiques ($(echo "$REF_REGL" | tr '\n' ';'))" "[[ '$(reglages ged_restauree)' == '$REF_REGL' ]]"
verifier "données restaurées" "[[ '$(q ged_restauree 'select count(*) from ged.document')' == 25 ]]"
verifier "search_path de ged_app actif" "[[ '$(psql -X -At -U ged_app -d ged_restauree -c 'show search_path')' == 'ged' ]]"
verifier "CONNECT refusé à un rôle hors des trois" "! psql -X -At -U intrus -d ged_restauree -c 'select 1' >/dev/null 2>&1"

bilan
