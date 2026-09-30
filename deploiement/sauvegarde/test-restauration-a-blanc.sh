#!/usr/bin/env bash
# =====================================================================
#  test-restauration-a-blanc.sh — exercice de restauration exécutable
#  (DAT 6.5 : « testée à blanc avant la mise en production puis chaque
#  semestre, avec compte rendu »).
#
#  Usage : test-restauration-a-blanc.sh [--base NOM] [--documents N]
#                                       [--travail DIR] [--avec-pitr]
#
#  Déroulé, avec les VRAIS scripts de sauvegarde et de restauration :
#    1. jeu d'essai : N documents, leurs clés (cle_fichier) et leurs fichiers
#       chiffrés aa/bb/<uuid>.enc, un keystore et un fichier de secrets ;
#    2. sauvegarde de la base, puis activité simulée (3 dépôts, 1 fichier
#       perdu), puis sauvegarde des fichiers et des clés ;
#    3. restauration complète dans une base et des répertoires NEUFS ;
#    4. contrôles : contenu des tables identique à l'instant de la
#       sauvegarde, keystore et secrets identiques, fichiers restaurés
#       identiques bit à bit sur un échantillon, rapprochement (3 orphelins,
#       1 document à ré-importer) ;
#    5. avec --avec-pitr : restauration À UN INSTANT DONNÉ (sauvegarde
#       physique + WAL archivés) sur une instance PostgreSQL jetable, créée
#       dans le répertoire de travail (port 55432/55433), jamais sur
#       l'instance partagée.
#  Compte rendu : <travail>/compte-rendu.txt (durées, écart au RTO, résultats).
#
#  Le jeu d'essai vit dans le schéma essai_restauration de la base NOM : il
#  ne touche ni au schéma de l'application ni à aucune autre base. La base
#  de restauration NOM_restauration est recréée à chaque exécution.
#  Connexion : variables PG* (défaut : postgres@localhost:5432).
# =====================================================================
set -Eeuo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$DIR/../scripts/commun.sh"

BASE="ged_dev2"; N=500; TRAVAIL=""; PITR=non
while [[ $# -gt 0 ]]; do
    case "$1" in
        --base) BASE="$2"; shift 2 ;;
        --documents) N="$2"; shift 2 ;;
        --travail) TRAVAIL="$2"; shift 2 ;;
        --avec-pitr) PITR=oui; shift ;;
        *) echec "option inconnue : $1" ;;
    esac
done
[[ "$BASE" =~ ^[a-z0-9_]+$ ]] || echec "nom de base invalide : $BASE"
CIBLE="${BASE}_restauration"
SCHEMA=essai_restauration
TRAVAIL="${TRAVAIL:-$(mktemp -d)}"
export PGHOST="${PGHOST:-localhost}" PGPORT="${PGPORT:-5432}" PGUSER="${PGUSER:-postgres}" PGOPTIONS="-c client_min_messages=warning"
[[ -n "${PG_BIN:-}" ]] && export PATH="$PG_BIN:$PATH"
exiger_commandes psql pg_dump pg_restore createdb dropdb gpg sha256sum comm

rm -rf "$TRAVAIL/source" "$TRAVAIL/sauvegardes" "$TRAVAIL/sauvegardes-cles" "$TRAVAIL/restauration"
mkdir -p "$TRAVAIL"/{source/fichiers,source/cles,sauvegardes,sauvegardes-cles,restauration}
CR="$TRAVAIL/compte-rendu.txt"
: > "$CR"
export GED_JOURNAL="$TRAVAIL/journal.log"
: > "$GED_JOURNAL"
note() { journal "$*"; echo "$*" >> "$CR"; }
psql_base() { psql -X -q -v ON_ERROR_STOP=1 -At -d "$1" -c "$2" | tr -d '\r'; }
ECHECS=0
controle() {   # controle "libellé" attendu obtenu
    if [[ "$2" == "$3" ]]; then note "  [OK]    $1 : $3"; else note "  [ÉCHEC] $1 : attendu « $2 », obtenu « $3 »"; ECHECS=$((ECHECS + 1)); fi
}
DEBUT_TOTAL=$SECONDS

note "Exercice de restauration à blanc — $(date '+%Y-%m-%d %H:%M:%S %z')"
note "Poste : $(uname -s) ; PostgreSQL : $(psql_base postgres 'show server_version') ; base source : $BASE ; documents : $N"

# ---------------------------------------------------------------------
# 1. Jeu d'essai
# ---------------------------------------------------------------------
if [[ "$(psql_base postgres "select count(*) from pg_database where datname = '$BASE'")" == 0 ]]; then
    createdb "$BASE"
fi
psql -X -q -v ON_ERROR_STOP=1 -d "$BASE" <<SQL
drop schema if exists $SCHEMA cascade;
create schema $SCHEMA;
-- Structure alignée sur le lot stockage (cle_fichier, version_document) :
-- un fichier chiffré aa/bb/<cle_fichier.id>.enc par version.
create table $SCHEMA.cle_fichier (
    id uuid primary key, dek_enveloppee bytea not null, kek_id varchar(64) not null,
    cree_le timestamptz not null default now());
create table $SCHEMA.document (
    id uuid primary key default gen_random_uuid(), nom text not null,
    depose_le timestamptz not null default now());
create table $SCHEMA.version_document (
    id uuid primary key default gen_random_uuid(),
    document_id uuid not null references $SCHEMA.document,
    fichier_id uuid not null unique references $SCHEMA.cle_fichier,
    empreinte char(64), taille_octets bigint);
SQL

creer_documents() {   # creer_documents NOMBRE PREFIXE
    local nombre="$1" prefixe="$2" id taille
    psql_base "$BASE" "
        with d as (insert into $SCHEMA.document(nom)
                   select '$prefixe-' || g from generate_series(1, $nombre) g returning id),
             c as (insert into $SCHEMA.cle_fichier(id, dek_enveloppee, kek_id)
                   select gen_random_uuid(), decode(md5(random()::text) || md5(random()::text), 'hex'), 'kek-2026'
                   from generate_series(1, $nombre) returning id),
             appariement as (select d.id as document_id, c.id as fichier_id
                   from (select id, row_number() over () r from d) d
                   join (select id, row_number() over () r from c) c using (r))
        insert into $SCHEMA.version_document(document_id, fichier_id)
        select document_id, fichier_id from appariement returning fichier_id" > "$TRAVAIL/ids-$prefixe"
    local maj="$TRAVAIL/maj-$prefixe.sql"
    : > "$maj"
    while read -r id; do
        mkdir -p "$TRAVAIL/source/fichiers/${id:0:2}/${id:2:2}"
        taille=$(( (RANDOM % 64 + 1) * 1024 ))
        head -c "$taille" /dev/urandom > "$TRAVAIL/source/fichiers/${id:0:2}/${id:2:2}/$id.enc"
        echo "update $SCHEMA.version_document set empreinte = '$(sha256sum "$TRAVAIL/source/fichiers/${id:0:2}/${id:2:2}/$id.enc" | cut -d' ' -f1)', taille_octets = $taille where fichier_id = '$id';" >> "$maj"
    done < "$TRAVAIL/ids-$prefixe"
    psql -X -q -v ON_ERROR_STOP=1 -d "$BASE" -f "$maj"
}
creer_documents "$N" initial
head -c 4096 /dev/urandom > "$TRAVAIL/source/cles/ged-kek.p12"          # keystore factice
printf 'GED_KEYSTORE_MDP=essai-restauration\nDB_PASSWORD=essai\n' > "$TRAVAIL/source/cles/ged.env"

# Clé GPG d'essai (le « responsable sécurité ») : jetable. Répertoire court :
# le chemin de la socket de gpg-agent est limité à une centaine de caractères.
export GNUPGHOME
GNUPGHOME="$(mktemp -d "${TMPDIR:-/tmp}/ged-gpg.XXXXXX")"
trap 'gpgconf --kill gpg-agent 2>/dev/null || true; rm -rf "$GNUPGHOME"' EXIT
chmod 0700 "$GNUPGHOME"
gpg --batch --quiet --pinentry-mode loopback --passphrase '' \
    --quick-gen-key 'Essai restauration GED <essai-restauration@ged.invalid>' rsa3072 encr never 2>/dev/null \
    || echec "génération de la clé GPG d'essai impossible"

cat > "$TRAVAIL/sauvegarde.env" <<ENV
PGDATABASE=$BASE
GED_SAUVEGARDE_DESTINATION=$TRAVAIL/sauvegardes
GED_SAUVEGARDE_CLES_DESTINATION=$TRAVAIL/sauvegardes-cles
GED_SAUVEGARDE_GPG_DESTINATAIRE=essai-restauration@ged.invalid
GED_STOCKAGE_RACINE=$TRAVAIL/source/fichiers
GED_KEYSTORE_CHEMIN=$TRAVAIL/source/cles/ged-kek.p12
GED_SAUVEGARDE_SECRETS=$TRAVAIL/source/cles/ged.env
GED_SCHEMA=$SCHEMA
ENV
export GED_SAUVEGARDE_ENV="$TRAVAIL/sauvegarde.env" GED_IGNORER_PERMISSIONS=oui GED_SCHEMA="$SCHEMA"

# ---------------------------------------------------------------------
# 2. Sauvegarde, avec activité entre la base et les fichiers
# ---------------------------------------------------------------------
note ""
note "1. Sauvegarde"
empreinte_tables() {   # contenu complet des trois tables, ordonné : une seule empreinte
    psql_base "$1" "select md5(string_agg(x, '|' order by x)) from (
        select 'c' || id || encode(dek_enveloppee, 'hex') || kek_id as x from $SCHEMA.cle_fichier
        union all select 'd' || id || nom from $SCHEMA.document
        union all select 'v' || id || document_id || fichier_id || coalesce(empreinte, '') || coalesce(taille_octets, 0) from $SCHEMA.version_document) t"
}
t=$SECONDS
"$DIR/sauvegarder-base.sh" --logique >/dev/null
note "   base sauvegardée en $((SECONDS - t)) s"
REF_EMPREINTE="$(empreinte_tables "$BASE")"
REF_DOCUMENTS="$(psql_base "$BASE" "select count(*) from $SCHEMA.document")"
REF_CLES="$(psql_base "$BASE" "select count(*) from $SCHEMA.cle_fichier")"

# Activité entre les deux sauvegardes : 3 dépôts (futurs orphelins) et un
# fichier perdu (futur document « à ré-importer »).
creer_documents 3 apres-base
PERDU="$(head -1 "$TRAVAIL/ids-initial")"
rm "$TRAVAIL/source/fichiers/${PERDU:0:2}/${PERDU:2:2}/$PERDU.enc"
note "   activité simulée : 3 dépôts après la sauvegarde de base, fichier $PERDU perdu"

t=$SECONDS
"$DIR/sauvegarder-fichiers.sh" >/dev/null
note "   fichiers sauvegardés en $((SECONDS - t)) s"
t=$SECONDS
"$DIR/sauvegarder-cles.sh" >/dev/null
note "   clés sauvegardées (archive GPG) en $((SECONDS - t)) s"
ARCHIVE_CLES="$(ls "$TRAVAIL"/sauvegardes-cles/*-cles.tar.gpg | head -1)"
# Prise après les fichiers, l'archive des clés couvre aussi les dépôts postérieurs à la base.
REF_CLES_ARCHIVE="$(psql_base "$BASE" "select count(*) from $SCHEMA.cle_fichier")"
if ! tar -tf "$ARCHIVE_CLES" >/dev/null 2>&1; then
    note "  [OK]    archive des clés illisible sans la clé privée (chiffrée)"
else
    note "  [ÉCHEC] archive des clés lisible en clair"; ECHECS=$((ECHECS + 1))
fi

# ---------------------------------------------------------------------
# 3. Restauration dans des emplacements neufs
# ---------------------------------------------------------------------
note ""
note "2. Restauration"
dropdb --if-exists "$CIBLE"
SAUV_BASE="$(ls -d "$TRAVAIL"/sauvegardes/base/*/ | head -1)"; SAUV_BASE="${SAUV_BASE%/}"
DEBUT_RESTAURATION=$SECONDS
t=$SECONDS
"$DIR/restaurer.sh" base-logique --sauvegarde "$SAUV_BASE" --cible "$CIBLE" --sans-proprietaires >/dev/null
note "   base restaurée dans $CIBLE en $((SECONDS - t)) s"
t=$SECONDS
"$DIR/restaurer.sh" fichiers --sauvegarde-base "$SAUV_BASE" --cible "$TRAVAIL/restauration/fichiers" >/dev/null
note "   fichiers restaurés en $((SECONDS - t)) s"
t=$SECONDS
"$DIR/restaurer.sh" cles --archive "$ARCHIVE_CLES" --cible "$TRAVAIL/restauration/cles" >/dev/null
note "   clés restaurées en $((SECONDS - t)) s"
t=$SECONDS
"$DIR/rapprocher-orphelins.sh" --base "$CIBLE" --racine "$TRAVAIL/restauration/fichiers" \
    --appliquer --rapport "$TRAVAIL/rapprochement.txt" >/dev/null
note "   rapprochement en $((SECONDS - t)) s"
DUREE_RESTAURATION=$((SECONDS - DEBUT_RESTAURATION))

# ---------------------------------------------------------------------
# 4. Contrôles
# ---------------------------------------------------------------------
note ""
note "3. Contrôles"
controle "documents restaurés" "$REF_DOCUMENTS" "$(psql_base "$CIBLE" "select count(*) from $SCHEMA.document")"
controle "clés de fichier restaurées" "$REF_CLES" "$(psql_base "$CIBLE" "select count(*) from $SCHEMA.cle_fichier")"
controle "contenu des tables identique à l'instant de la sauvegarde (md5)" "$REF_EMPREINTE" "$(empreinte_tables "$CIBLE")"
controle "keystore identique (SHA-256)" "$(sha256sum < "$TRAVAIL/source/cles/ged-kek.p12" | cut -d' ' -f1)" "$(sha256sum < "$TRAVAIL/restauration/cles/cles/ged-kek.p12" | cut -d' ' -f1)"
controle "secrets restaurés (SHA-256)" "$(sha256sum < "$TRAVAIL/source/cles/ged.env" | cut -d' ' -f1)" "$(sha256sum < "$TRAVAIL/restauration/cles/cles/ged.env" | cut -d' ' -f1)"
controle "cle_fichier de l'archive des clés : toutes les clés au moment de la sauvegarde" "$REF_CLES_ARCHIVE" "$(tr -d '\r' < "$TRAVAIL/restauration/cles/cles/cle_fichier.nombre")"
controle "orphelins détectés et mis en quarantaine" "3" "$(grep -c '^ORPHELIN ' "$TRAVAIL/rapprochement.txt")"
controle "document à ré-importer signalé" "A_REIMPORTER $PERDU" "$(grep '^A_REIMPORTER ' "$TRAVAIL/rapprochement.txt")"
controle "orphelins retirés du référentiel restauré" "$((REF_CLES - 1))" "$(find "$TRAVAIL/restauration/fichiers" -name '*.enc' | wc -l | tr -d ' ')"

# Intégrité par échantillonnage (DAT 6.5) : 20 fichiers tirés au hasard,
# empreinte recalculée comparée à celle enregistrée en base.
ech_ok=0; ech_total=0
while IFS='|' read -r id empreinte; do
    ech_total=$((ech_total + 1))
    f="$TRAVAIL/restauration/fichiers/${id:0:2}/${id:2:2}/$id.enc"
    [[ -f "$f" && "$(sha256sum "$f" | cut -d' ' -f1)" == "$empreinte" ]] && ech_ok=$((ech_ok + 1))
done < <(psql_base "$CIBLE" "select fichier_id, empreinte from $SCHEMA.version_document
                             where fichier_id <> '$PERDU' order by random() limit 20")
controle "échantillon de 20 fichiers : empreinte conforme" "20/20" "$ech_ok/$ech_total"

# ---------------------------------------------------------------------
# 5. Restauration à un instant donné (facultative, instance jetable)
# ---------------------------------------------------------------------
if [[ "$PITR" == oui ]]; then
    note ""
    note "4. Restauration à un instant donné (sauvegarde physique + WAL archivés)"
    exiger_commandes initdb pg_ctl pg_basebackup
    P="$TRAVAIL/pitr"; rm -rf "$P"; mkdir -p "$P/wal"
    initdb -D "$P/source" -U postgres -A trust -E UTF8 --no-instructions >/dev/null
    # Sous Windows, archive_command passe par cmd.exe : on appelle bash explicitement.
    ARCHIVEUR="$DIR/archiver-wal.sh"
    if [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* ]]; then
        # Barres obliques : postgresql.conf interprète les barres inverses.
        BASH_WIN="$(cygpath -m "$(command -v bash)")"
        commande="\"$BASH_WIN\" \"$(cygpath -u "$ARCHIVEUR")\" \"%p\" \"%f\" \"$(cygpath -u "$P/wal")\""
    else
        commande="\"$ARCHIVEUR\" \"%p\" \"%f\" \"$P/wal\""
    fi
    cat >> "$P/source/postgresql.auto.conf" <<CONF
port = 55432
listen_addresses = 'localhost'
wal_level = replica
archive_mode = on
archive_command = '$commande'
archive_timeout = 60
CONF
    pg_ctl -D "$P/source" -l "$P/source.log" -w start >/dev/null
    export PGPORT=55432
    psql_base postgres "create table essai(id int, lot text); insert into essai select g, 'A' from generate_series(1,1000) g" >/dev/null
    cat > "$TRAVAIL/sauvegarde-pitr.env" <<ENV
PGDATABASE=postgres
PGPORT=55432
GED_SAUVEGARDE_DESTINATION=$P/sauvegardes
ENV
    GED_SAUVEGARDE_ENV="$TRAVAIL/sauvegarde-pitr.env" "$DIR/sauvegarder-base.sh" --physique >/dev/null 2>&1
    psql_base postgres "insert into essai select g, 'B' from generate_series(1,500) g" >/dev/null
    INSTANT="$(psql_base postgres "select now()")"
    sleep 2
    psql_base postgres "insert into essai select g, 'C' from generate_series(1,250) g" >/dev/null
    # Clôt le segment en cours et attend qu'il soit archivé.
    SEGMENT="$(psql_base postgres "select pg_walfile_name(pg_switch_wal())")"
    for _ in $(seq 1 60); do
        [[ "$(psql_base postgres "select coalesce(last_archived_wal >= '$SEGMENT', false) from pg_stat_archiver")" == t ]] && break
        sleep 1
    done
    note "   instance jetable : lots A (avant la sauvegarde), B (avant l'instant cible), C (après)"
    note "   instant cible : $INSTANT ; segments archivés : $(ls "$P/wal" | wc -l | tr -d ' ')"
    pg_ctl -D "$P/source" -m fast -w stop >/dev/null
    SAUV_PHYS="$(ls -d "$P"/sauvegardes/base/*/ | head -1)"; SAUV_PHYS="${SAUV_PHYS%/}"
    t=$SECONDS
    "$DIR/restaurer.sh" base-physique --sauvegarde "$SAUV_PHYS" --pgdata "$P/restaure" \
        --instant "$INSTANT" --wal "$P/wal" >/dev/null
    sed -i 's/^port = 55432/port = 55433/' "$P/restaure/postgresql.auto.conf"
    pg_ctl -D "$P/restaure" -l "$P/restaure.log" -w -t 120 start >/dev/null
    for _ in $(seq 1 60); do
        [[ "$(PGPORT=55433 psql_base postgres "select pg_is_in_recovery()")" == f ]] && break
        sleep 1
    done
    note "   restauration à l'instant cible en $((SECONDS - t)) s"
    export PGPORT=55433
    controle "lot A (antérieur à la sauvegarde) présent" "1000" "$(psql_base postgres "select count(*) from essai where lot = 'A'")"
    controle "lot B (antérieur à l'instant cible) rejoué depuis les WAL" "500" "$(psql_base postgres "select count(*) from essai where lot = 'B'")"
    controle "lot C (postérieur à l'instant cible) absent" "0" "$(psql_base postgres "select count(*) from essai where lot = 'C'")"
    pg_ctl -D "$P/restaure" -m fast -w stop >/dev/null
    export PGPORT=5432
fi

# ---------------------------------------------------------------------
# Bilan
# ---------------------------------------------------------------------
note ""
note "Bilan"
note "   durée de la restauration (base, fichiers, clés, rapprochement) : ${DUREE_RESTAURATION} s pour $N documents"
note "   RTO du DAT : 8 h (28 800 s) — écart : $((28800 - DUREE_RESTAURATION)) s de marge sur ce volume"
note "   durée totale de l'exercice : $((SECONDS - DEBUT_TOTAL)) s"
if (( ECHECS == 0 )); then
    note "   RÉSULTAT : RÉUSSI"
else
    note "   RÉSULTAT : $ECHECS contrôle(s) en échec"
    exit 1
fi
