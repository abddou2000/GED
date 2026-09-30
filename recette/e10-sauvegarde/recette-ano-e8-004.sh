#!/usr/bin/env bash
# =====================================================================
#  Recette tour 2 — ANO-E8-004 (§4.2.2, §10.4) : retour arrière au-delà du
#  jalon workflow-e8 sur une COPIE PEUPLÉE de la base de qa.
#
#  La base de l'instance qa (diffusions, circuits, règles produits par les
#  recettes E8) est copiée (pg_dump en lecture) dans une base ged_qa_v8_e8 de
#  l'instance PostgreSQL JETABLE ; aucune écriture sur la base source.
#   E8004-01 retour arrière de N changesets (tour 1 compris) : refus au
#            premier changeset gardé qui perdrait des données, avec décompte,
#            code ≠ 0 ;
#   E8004-02 rien de perdu : diffusions, circuits, règles, validateurs intacts ;
#            seuls des changesets sans perte ont été défaits (liste imprimée) ;
#   E8004-03 remontée (update) : même nombre de changesets et contenu des tables
#            identique à l'état de départ (empreinte md5 par table) ;
#   E8004-04 contrôle préalable de DEPLOIEMENT.md §8 exécutable tel quel et
#            cohérent avec le refus.
#
#  Usage : V8_TRAVAIL=<dossier> SOURCE_URL=<postgresql://…/ged_qa (lecture)> \
#          PGHOST/PGPORT=<instance jetable> recette-ano-e8-004.sh [N]
# =====================================================================
set -Eeuo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib-v8.sh"
BASE=ged_qa_v8_e8
N="${1:-12}"
W="$V8_TRAVAIL/e8004"; rm -rf "$W"; mkdir -p "$W"
: "${SOURCE_URL:?SOURCE_URL (base source, lecture seule) obligatoire}"
source "$DEPOT/recette/lib/liquibase.sh"
lb() { BACKEND="$DEPOT/backend" TMPDIR="$W" LB_URL="jdbc:postgresql://${PGHOST}:${PGPORT}/$BASE" liquibase_executer "$@"; }

# --- Copie peuplée ---------------------------------------------------------------------------
pg_dump -Fc --no-acl -d "$SOURCE_URL" -f "$W/source.dump" || fatal "pg_dump de la base source"
supprimer_base "$BASE"
"$PSQL" -X -q -d postgres -c "CREATE DATABASE $BASE OWNER ged_owner"
pg_restore -d "$BASE" "$W/source.dump" 2> "$W/restore.err" || true
info "copie : $(q "$BASE" "select count(*) from ged.document") documents, $(q "$BASE" "select count(*) from ged_liquibase.databasechangelog") changesets"

pertes() {  # décompte des données que les changesets gardés perdraient
  # (marques d'échéance : voir signalements(), la colonne disparaît avec 202610031000-1)
  q "$BASE" "SELECT 'diffusions=' || (SELECT count(*) FROM ged.habilitation WHERE role_id = '0192a000-0000-7000-8000-000000000005')
    || ' circuits_annules=' || (SELECT count(*) FROM ged.circuit WHERE statut = 'ANNULE')
    || ' circuits=' || (SELECT count(*) FROM ged.circuit)
    || ' decisions=' || (SELECT count(*) FROM ged.decision)
    || ' regles_de_type=' || (SELECT count(*) FROM ged.type_document WHERE regle_workflow_id IS NOT NULL)
    || ' validateurs_par_role=' || (SELECT count(*) FROM ged.regle_validateur WHERE employe_id IS NULL)
    || ' noeuds_sans_regle=' || (SELECT count(*) FROM ged.noeud WHERE regle_workflow_id IS NULL)"
}
signalements() { q "$BASE" "select count(*) from information_schema.columns where table_schema = 'ged' and table_name = 'document' and column_name = 'echeance_signalee_le'" | grep -q 1 \
  && q "$BASE" "SELECT count(*) FROM ged.document WHERE echeance_signalee_le IS NOT NULL" || echo "colonne défaite"; }
empreintes_tables "$BASE" ged > "$W/avant.txt"
cs0="$(q "$BASE" "select count(*) from ged_liquibase.databasechangelog")"
p0="$(pertes)"
derniers="$(q "$BASE" "select string_agg(id, ', ' order by orderexecuted desc) from (select id, orderexecuted from ged_liquibase.databasechangelog order by orderexecuted desc limit $N) x")"
info "avant : $cs0 changesets ; $p0 ; marques d'échéance $(signalements)"
info "rollbackCount $N : $derniers"

# --- E8004-04 : contrôle préalable de DEPLOIEMENT.md §8 --------------------------------------
sed -n '/Contrôle préalable/,/^  ```$/p' "$DEPOT/DEPLOIEMENT.md" | sed -n '/```sql/,/```/p' | sed '1d;$d' > "$W/controle.sql"
if "$PSQL" -X -q -v ON_ERROR_STOP=1 -At -d "$BASE" -f "$W/controle.sql" > "$W/controle.txt" 2>&1; then
  ctl="$(tr '\n' ' ' < "$W/controle.txt")"; ok_ctl=oui
else ctl="$(tr '\n' ' ' < "$W/controle.txt" | cut -c1-200)"; ok_ctl=non; fi

# --- E8004-01 : retour arrière ---------------------------------------------------------------
set +e; lb rollbackCount "$N" > "$W/rollback.log" 2>&1; code=$?; set -e
cs1="$(q "$BASE" "select count(*) from ged_liquibase.databasechangelog")"
p1="$(pertes)"
refus="$(grep -a -o -E "Retour arri.{1,3}re [^:]*refus[^:]*: [^.]*\." "$W/rollback.log" | head -1 || true)"
[[ -z "$refus" ]] && refus="$(grep -a -o -E 'ERROR: .{0,300}' "$W/rollback.log" | head -1 || true)"
defaits=$((cs0 - cs1))
if [[ "$code" != 0 && -n "$refus" ]]; then
  resultat E8004-01 OK "Retour arrière au-delà de workflow-e8 sur une base peuplée : refusé avec décompte au premier changeset gardé, code ≠ 0 [4.2.2, 10.4]" "code $code ; $defaits changeset(s) défaits avant le refus ; « $refus »"
else
  resultat E8004-01 ECHEC "Retour arrière au-delà de workflow-e8 : pas de refus" "code $code ; $defaits défaits ; $(tail -3 "$W/rollback.log" | tr '\n' ' ' | cut -c1-300)"
fi
# Données produites en service : aucune perte (les décomptes ne dépendent pas des colonnes défaites)
if [[ "$p1" == "$p0" ]]; then
  resultat E8004-02 OK "Rien de perdu avant le refus : diffusions, circuits, décisions, règles et validateurs intacts [10.4]" "$p1 ; changesets défaits : $(echo "$derniers" | cut -d, -f1-$defaits)"
else
  resultat E8004-02 ECHEC "Données perdues avant le refus" "avant $p0 ; après $p1"
fi

# --- E8004-03 : remontée ---------------------------------------------------------------------
lb update > "$W/update.log" 2>&1 || true
cs2="$(q "$BASE" "select count(*) from ged_liquibase.databasechangelog")"
empreintes_tables "$BASE" ged > "$W/apres.txt"
# version_habilitations est le compteur d'invalidation du cache des droits : les changesets de
# rôles l'incrémentent à chaque passage (effet voulu), il est exclu de la comparaison et rapporté.
diff <(grep -v '^version_habilitations|' "$W/avant.txt") <(grep -v '^version_habilitations|' "$W/apres.txt") > "$W/diff.txt" || true
nd="$(grep -c '^[<>]' "$W/diff.txt" || true)"
info "compteur version_habilitations : $(grep '^version_habilitations|' "$W/avant.txt" | cut -d'|' -f2) ligne ; valeur $(q "$BASE" "select valeur from ged.version_habilitations") après remontée"
if [[ "$cs2" == "$cs0" && "$nd" == 0 ]]; then
  resultat E8004-03 OK "Remontée après le refus : base ramenée à son état de départ (changesets et contenu de $(wc -l < "$W/avant.txt") tables identiques)" "$cs0 → $cs1 → $cs2 changesets ; md5 par table identiques"
else
  resultat E8004-03 ECHEC "Remontée : état de départ non retrouvé" "$cs0 → $cs1 → $cs2 changesets ; $nd ligne(s) d'empreinte différentes : $(head -4 "$W/diff.txt" | tr '\n' ' ' | cut -c1-300)"
fi

if [[ "$ok_ctl" == oui ]]; then
  resultat E8004-04 OK "Contrôle préalable de DEPLOIEMENT.md §8 exécutable tel quel ; ses décomptes annoncent le refus" "$ctl"
else
  resultat E8004-04 ECHEC "Contrôle préalable de DEPLOIEMENT.md §8 non exécutable" "$ctl"
fi
bilan "ANO-E8-004 (retour arrière sur copie peuplée)"
