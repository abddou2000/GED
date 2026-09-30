#!/usr/bin/env bash
# =====================================================================
#  Test de rapprocher-orphelins.sh (DAT 6.5, P-13 ; ANO-E10-007) sur une
#  instance PostgreSQL jetable.
#    R1  rapport : manquant signalé, aperçu en cache NON signalé
#    R2  --appliquer refusé tant qu'une session du rôle applicatif est ouverte
#    R3  --appliquer : orphelin ancien en quarantaine, orphelin récent laissé,
#        fichier mal rangé traité sans erreur, fichiers référencés intacts
#    R4  purge refusée tant que l'application tourne
#    R5  purge : un fichier redevenu référencé est remis en place, les autres
#        sont détruits, le lot disparaît
#  Usage : test-rapprocher-orphelins.sh   (code 0 si tout est conforme)
# =====================================================================
set -Eeuo pipefail
ICI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$ICI/instance-jetable.sh"
RAPP="${RAPP:-$ICI/../rapprocher-orphelins.sh}"   # surchargeable : rejouer le test sur une autre version

instance_demarrer
trap 'kill "${PID_APP:-0}" 2>/dev/null || true; instance_arreter' EXIT
W="$JETABLE/travail"; RACINE="$W/fichiers"; CACHE="$W/cache-apercu"; QUAR="$W/quarantaine"
mkdir -p "$RACINE" "$CACHE"
export GED_JOURNAL="$W/journal.log"

q() { psql -X -q -v ON_ERROR_STOP=1 -At -d essai -c "$1" | tr -d '\r'; }
chemin() { echo "${1:0:2}/${1:2:2}/$1.enc"; }
fichier() {  # racine id [âge]
    mkdir -p "$1/${2:0:2}/${2:2:2}"; head -c 2048 /dev/urandom > "$1/$(chemin "$2")"
    touch -d "${3:-3 hours ago}" "$1/$(chemin "$2")"
}
uuid() { cat /proc/sys/kernel/random/uuid; }
cle() { q "insert into ged.cle_fichier(id) values ('$1')"; }

psql -X -q -d postgres -c "create database essai" -c "create role ged_app login"
q "create schema ged;
   create table ged.cle_fichier(id uuid primary key);
   create table ged.version_document(id serial primary key, cle_fichier_id uuid references ged.cle_fichier);
   create table ged.copie_conservation(id serial primary key, cle_fichier_id uuid references ged.cle_fichier);
   grant usage on schema ged to ged_app; grant select on all tables in schema ged to ged_app"

VERSION="$(uuid)"; COPIE="$(uuid)"; PERDU="$(uuid)"; APERCU="$(uuid)"
ORPH="$(uuid)"; RECENT="$(uuid)"; MAL="$(uuid)"
for id in "$VERSION" "$PERDU"; do cle "$id"; q "insert into ged.version_document(cle_fichier_id) values ('$id')"; done
cle "$COPIE"; q "insert into ged.copie_conservation(cle_fichier_id) values ('$COPIE')"
cle "$APERCU"                                   # clé d'un aperçu : aucune table ne la référence
fichier "$RACINE" "$VERSION"; fichier "$RACINE" "$COPIE"; fichier "$CACHE" "$APERCU"
fichier "$RACINE" "$ORPH"; fichier "$RACINE" "$RECENT" now
mkdir -p "$RACINE/zz/zz"; head -c 512 /dev/urandom > "$RACINE/zz/zz/$MAL.enc"; touch -d "3 hours ago" "$RACINE/zz/zz/$MAL.enc"

echo "R1 — rapport seul"
( cd "$W" && "$RAPP" --base essai --racine "$RACINE" --quarantaine "$QUAR" --rapport "$W/r1.txt" ) >/dev/null
verifier "manquant signalé A_REIMPORTER" "grep -qx 'A_REIMPORTER $PERDU' '$W/r1.txt'"
verifier "aperçu en cache non signalé" "! grep -q '$APERCU' '$W/r1.txt'"
verifier "un seul manquant" "[[ \$(grep -c '^A_REIMPORTER ' '$W/r1.txt') == 1 ]]"
verifier "fichier mal rangé signalé" "grep -qx 'MAL_RANGE zz/zz/$MAL.enc' '$W/r1.txt'"
verifier "orphelin récent signalé RECENT" "grep -qx 'RECENT $(chemin "$RECENT")' '$W/r1.txt'"
verifier "rien déplacé sans --appliquer" "[[ ! -d '$QUAR' && -f '$RACINE/$(chemin "$ORPH")' ]]"

echo "R2 — application en marche"
psql -X -q -U ged_app -d essai -c "select pg_sleep(60)" >/dev/null 2>&1 &
PID_APP=$!
for _ in $(seq 1 50); do
    [[ "$(q "select count(*) from pg_stat_activity where usename = 'ged_app'")" == 1 ]] && break; sleep 0.1
done
set +e
( cd "$W" && "$RAPP" --base essai --racine "$RACINE" --quarantaine "$QUAR" --appliquer --rapport "$W/r2.txt" ) > "$W/r2.log" 2>&1
code=$?
set -e
verifier "--appliquer refusé (code non nul)" "[[ $code -ne 0 ]]"
verifier "message explicite" "grep -q 'application tourne' '$W/r2.log'"
verifier "aucun fichier déplacé" "[[ ! -d '$QUAR' && -f '$RACINE/$(chemin "$ORPH")' ]]"
kill "$PID_APP" 2>/dev/null || true; wait "$PID_APP" 2>/dev/null || true
q "select pg_terminate_backend(pid) from pg_stat_activity where usename = 'ged_app'" >/dev/null
for _ in $(seq 1 50); do
    [[ "$(q "select count(*) from pg_stat_activity where usename = 'ged_app'")" == 0 ]] && break; sleep 0.1
done

echo "R3 — --appliquer, application arrêtée"
set +e
( cd "$W" && "$RAPP" --base essai --racine "$RACINE" --quarantaine "$QUAR" --appliquer --rapport "$W/r3.txt" ) > "$W/r3.log" 2>&1
code=$?
set -e
LOT="$(ls -d "$QUAR"/*/ 2>/dev/null | head -1)"; LOT="${LOT%/}"
verifier "exécution réussie (fichier mal rangé compris)" "[[ $code -eq 0 ]]"
verifier "orphelin ancien en quarantaine" "[[ -f '$LOT/$(chemin "$ORPH")' && ! -e '$RACINE/$(chemin "$ORPH")' ]]"
verifier "orphelin mal rangé en quarantaine" "[[ -f '$LOT/zz/zz/$MAL.enc' ]]"
verifier "orphelin récent laissé en place" "[[ -f '$RACINE/$(chemin "$RECENT")' ]]"
verifier "fichiers référencés intacts" "[[ -f '$RACINE/$(chemin "$VERSION")' && -f '$RACINE/$(chemin "$COPIE")' ]]"

echo "R4 — purge, application en marche"
touch -d "9 days ago" "$LOT"
psql -X -q -U ged_app -d essai -c "select pg_sleep(60)" >/dev/null 2>&1 &
PID_APP=$!
for _ in $(seq 1 50); do
    [[ "$(q "select count(*) from pg_stat_activity where usename = 'ged_app'")" == 1 ]] && break; sleep 0.1
done
set +e
( cd "$W" && "$RAPP" --base essai --racine "$RACINE" --quarantaine "$QUAR" --purger-apres-jours 7 --rapport "$W/r4.txt" ) > "$W/r4.log" 2>&1
code=$?
set -e
verifier "purge refusée" "[[ $code -ne 0 && -d '$LOT' ]]"
kill "$PID_APP" 2>/dev/null || true; wait "$PID_APP" 2>/dev/null || true
q "select pg_terminate_backend(pid) from pg_stat_activity where usename = 'ged_app'" >/dev/null
for _ in $(seq 1 50); do
    [[ "$(q "select count(*) from pg_stat_activity where usename = 'ged_app'")" == 0 ]] && break; sleep 0.1
done

echo "R5 — purge avec recontrôle en base"
cle "$ORPH"                                     # l'orphelin redevient référencé
touch -d "9 days ago" "$LOT"
( cd "$W" && "$RAPP" --base essai --racine "$RACINE" --quarantaine "$QUAR" --purger-apres-jours 7 --rapport "$W/r5.txt" ) > "$W/r5.log" 2>&1
verifier "fichier redevenu référencé remis en place, non détruit" "[[ -f '$RACINE/$(chemin "$ORPH")' ]]"
verifier "fichier non référencé détruit" "[[ ! -e '$LOT/zz/zz/$MAL.enc' ]]"
verifier "lot de quarantaine supprimé" "[[ ! -d '$LOT' ]]"
verifier "remise en place journalisée" "grep -q 'remis en place' '$W/r5.log'"

bilan
