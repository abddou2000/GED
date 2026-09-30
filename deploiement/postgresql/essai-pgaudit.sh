#!/bin/bash
# =====================================================================
#  GED Marchica Med — essai de la configuration pgaudit (P-16) sur une
#  instance PostgreSQL JETABLE : répertoire temporaire, socket Unix seule
#  (aucun port TCP), détruite à la fin. Aucune instance existante n'est
#  touchée. Rejouable par qa et en UAT avant la mise en service.
#
#  Prérequis : binaires PostgreSQL 16 et paquet pgaudit
#  (postgresql-16-pgaudit), exécution en root (instance lancée sous le compte
#  postgres) ou sous le compte postgres.
#  Usage : bash deploiement/postgresql/essai-pgaudit.sh
#  Code de sortie : 0 si tous les contrôles passent.
# =====================================================================
set -u
BIN=${PG_BIN:-/usr/lib/postgresql/16/bin}
ICI=$(cd "$(dirname "$0")" && pwd)
D=$(mktemp -d /tmp/ged-essai-pgaudit.XXXXXX)
chmod 755 "$D"
en_pg() { if [ "$(id -u)" = 0 ]; then runuser -u postgres -- "$@"; else "$@"; fi; }
[ "$(id -u)" = 0 ] && chown postgres: "$D"
# GARDER=1 : instance arrêtée mais répertoire (et journal pg.log) conservés.
arreter() {
    en_pg "$BIN/pg_ctl" -D "$D/data" -m fast stop > /dev/null 2>&1
    if [ -n "${GARDER:-}" ]; then echo "conservé : $D"; else rm -rf "$D"; fi
}
trap arreter EXIT

ECHECS=0
controle() { # libellé, motif attendu (grep -E) dans le journal, présence attendue (1/0)
    if grep -Eq "$2" "$D/pg.log"; then trouve=1; else trouve=0; fi
    if [ "$trouve" = "$3" ]; then echo "OK     $1"; else echo "ECHEC  $1"; ECHECS=$((ECHECS + 1)); fi
}
q() { local u=$1; shift; en_pg "$BIN/psql" -X -q -v ON_ERROR_STOP=1 -h "$D" -U "$u" -d ged "$@"; }

echo "== instance jetable dans $D"
en_pg "$BIN/initdb" -D "$D/data" -U postgres --auth=trust -E UTF8 > /dev/null || exit 1
cat >> "$D/data/postgresql.conf" <<EOF
listen_addresses = ''
unix_socket_directories = '$D'
logging_collector = off
# Liste déjà en place chez l'exploitant : elle doit survivre à l'ajout de pgaudit.
shared_preload_libraries = 'pg_stat_statements'
include_if_exists = 'conf.d/pgaudit.conf'
EOF
mkdir -p "$D/data/conf.d" && cp "$ICI/pgaudit.conf.exemple" "$D/data/conf.d/pgaudit.conf"
[ "$(id -u)" = 0 ] && chown -R postgres: "$D/data"
en_pg "$BIN/pg_ctl" -D "$D/data" -l "$D/pg.log" -w start > /dev/null || { cat "$D/pg.log"; exit 1; }

echo "== activer-pgaudit.sh (ajout sans écraser) puis redémarrage"
en_pg bash "$ICI/activer-pgaudit.sh" -h "$D" -U postgres -d postgres
en_pg bash "$ICI/activer-pgaudit.sh" -h "$D" -U postgres -d postgres   # idempotent
en_pg "$BIN/pg_ctl" -D "$D/data" -l "$D/pg.log" -w restart > /dev/null || { cat "$D/pg.log"; exit 1; }
libs=$(en_pg "$BIN/psql" -X -A -t -h "$D" -U postgres -d postgres -c "SHOW shared_preload_libraries")
echo "shared_preload_libraries = $libs"
case "$libs" in *pg_stat_statements*pgaudit*) echo "OK     liste conservée et complétée";; *) echo "ECHEC  liste écrasée"; ECHECS=$((ECHECS + 1));; esac

echo "== base et rôles de la GED (simplifiés), comptes nominatifs"
en_pg "$BIN/psql" -X -q -h "$D" -U postgres -d postgres <<'SQL'
CREATE DATABASE ged;
CREATE ROLE ged_owner LOGIN; CREATE ROLE ged_app LOGIN; CREATE ROLE ged_readonly LOGIN;
CREATE ROLE ged_sauvegarde LOGIN REPLICATION IN ROLE pg_read_all_data;
SQL
q postgres -c "CREATE SCHEMA ged AUTHORIZATION ged_owner" \
  -c "SET ROLE ged_owner" \
  -c "CREATE TABLE ged.journal_audit(id bigserial PRIMARY KEY, action text)" \
  -c "CREATE TABLE ged.document(id int PRIMARY KEY, texte_extrait text)" \
  -c "GRANT USAGE ON SCHEMA ged TO ged_app, ged_readonly" \
  -c "GRANT SELECT, INSERT ON ged.journal_audit TO ged_app" \
  -c "GRANT USAGE ON SEQUENCE ged.journal_audit_id_seq TO ged_app" \
  -c "INSERT INTO ged.document VALUES (1, 'contenu confidentiel')" > /dev/null
q postgres -f "$ICI/pgaudit-roles.sql" > /dev/null || exit 1
# Compte nominatif créé APRÈS la première passe, puis script relancé (procédure).
q postgres -c "CREATE ROLE dba_amina LOGIN IN ROLE ged_dba, ged_owner" > /dev/null
q postgres -f "$ICI/pgaudit-roles.sql" > "$D/roles.txt" || exit 1
# Compte oublié du groupe ged_dba : couvert par le réglage par défaut.
q postgres -c "CREATE ROLE dba_oubli LOGIN IN ROLE ged_owner" > /dev/null
q postgres -f "$ICI/pgaudit-roles.sql" | grep -E "dba_|ged_|postgres"

echo "== scénarios"
q dba_amina -c "SELECT texte_extrait FROM ged.document /* essai-lecture-dba */" > /dev/null
q dba_amina -c "SET ROLE ged_owner" -c "UPDATE ged.document SET texte_extrait = 'modifié' WHERE id = 1" > /dev/null
# Valeur passée en paramètre lié : elle ne doit pas apparaître dans le journal.
printf '%s\n' "SELECT count(*) FROM ged.document WHERE texte_extrait = \$1 \\bind 'essai-param-secret' \\g" \
  | q dba_amina > /dev/null
q dba_oubli -c "SET ROLE ged_owner" -c "CREATE TABLE ged.essai_oubli(i int)" > /dev/null
q ged_app -c "INSERT INTO ged.journal_audit(action) VALUES ('essai-trafic-applicatif')" > /dev/null
q ged_owner -c "CREATE TABLE ged.essai_owner(i int)" > /dev/null
q postgres -c "SET pgaudit.log = 'none'" -c "DELETE FROM ged.journal_audit WHERE action = 'essai-evasion'" > /dev/null
# Un administrateur nominatif (non superutilisateur) ne peut pas couper la trace.
q dba_amina -c "SET pgaudit.log = 'none'" > "$D/dba-set.txt" 2>&1
q dba_amina -c "SELECT 1 FROM ged.document /* essai-apres-refus */" > /dev/null
en_pg "$BIN/pg_dump" -h "$D" -U ged_sauvegarde -d ged -t ged.document -f /dev/null
en_pg "$BIN/psql" -X -q -h "$D" -U ged_readonly -d ged -c "SELECT count(*) FROM ged.journal_audit" > /dev/null 2>&1
sleep 1

echo "== contrôles du journal de PostgreSQL"
controle "DBA nominatif : lecture tracée sous son nom" \
  "user=dba_amina .*AUDIT: SESSION,[0-9]+,[0-9]+,READ,SELECT,TABLE,ged.document" 1
controle "DBA nominatif : SET ROLE tracé" "user=dba_amina .*AUDIT: SESSION,.*MISC,SET,,,SET ROLE ged_owner" 1
controle "Après SET ROLE ged_owner : l'écriture reste tracée sous le compte de connexion" \
  "user=dba_amina .*AUDIT: SESSION,.*WRITE,UPDATE,TABLE,ged.document" 1
controle "Compte oublié du groupe ged_dba : DDL tracé par le réglage par défaut" \
  "user=dba_oubli .*AUDIT: SESSION,.*DDL,CREATE TABLE,TABLE,ged.essai_oubli" 1
controle "ged_owner : DDL tracé" "user=ged_owner .*AUDIT: SESSION,.*DDL,CREATE TABLE,TABLE,ged.essai_owner" 1
controle "Superutilisateur : pgaudit ne trace pas son propre SET pgaudit.log (limite connue)" \
  "user=postgres .*AUDIT: SESSION,[0-9]+,[0-9]+,MISC,SET,,,SET pgaudit.log" 0
controle "Superutilisateur : la coupure reste visible (log_statement)" \
  "user=postgres .*statement: SET pgaudit.log = 'none'" 1
controle "Superutilisateur : la requête faite après la coupure reste visible" \
  "user=postgres .*statement: DELETE FROM ged.journal_audit WHERE action = 'essai-evasion'" 1
if grep -q "permission denied to set parameter \"pgaudit.log\"" "$D/dba-set.txt"; then
    echo "OK     Administrateur nominatif : SET pgaudit.log refusé"
else
    echo "ECHEC  Administrateur nominatif : SET pgaudit.log accepté"; ECHECS=$((ECHECS + 1))
fi
controle "Administrateur nominatif : toujours tracé après sa tentative" \
  "user=dba_amina .*AUDIT: SESSION,.*essai-apres-refus" 1
controle "ged_sauvegarde (pg_dump) : lecture tracée" \
  "user=ged_sauvegarde .*AUDIT: SESSION,.*READ,SELECT,TABLE,ged.document" 1
controle "ged_readonly : lecture tracée (même refusée)" "user=ged_readonly .*(AUDIT|permission denied)" 1
controle "ged_app : trafic applicatif non tracé par pgaudit (journal applicatif)" \
  "user=ged_app .*AUDIT:" 0
controle "Requête à paramètre lié tracée" "user=dba_amina .*AUDIT: SESSION,.*texte_extrait = \\\$1.*<not logged>" 1
controle "Valeur du paramètre jamais écrite (log_parameter off)" "essai-param-secret" 0
controle "Connexions tracées (log_connections)" "user=dba_amina .*connection authorized" 1

echo "== bilan : $ECHECS échec(s)"
[ "$ECHECS" = 0 ]
