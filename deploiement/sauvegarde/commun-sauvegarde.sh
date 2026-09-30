#!/usr/bin/env bash
# =====================================================================
#  Fonctions communes aux scripts de sauvegarde et de restauration.
#  À sourcer. Charge /etc/ged/sauvegarde.env (ou $GED_SAUVEGARDE_ENV).
# =====================================================================
DIR_SAUVEGARDE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../scripts/commun.sh
source "$DIR_SAUVEGARDE/../scripts/commun.sh"

charger_env "${GED_SAUVEGARDE_ENV:-${GED_CONF_DIR:-/etc/ged}/sauvegarde.env}"

: "${GED_SAUVEGARDE_DESTINATION:?GED_SAUVEGARDE_DESTINATION absente}"
DEST_BASE="$GED_SAUVEGARDE_DESTINATION/base"
DEST_FICHIERS="$GED_SAUVEGARDE_DESTINATION/fichiers"
DEST_CLES="${GED_SAUVEGARDE_CLES_DESTINATION:-}"
RETENTION_JOURS="${GED_SAUVEGARDE_RETENTION_JOURS:-30}"
RETENTION_MOIS="${GED_SAUVEGARDE_RETENTION_MOIS:-12}"

# Chaque sauvegarde est un répertoire horodaté qui ne reçoit son marqueur
# TERMINE qu'une fois complète et vérifiée : une sauvegarde interrompue n'est
# jamais prise pour une sauvegarde valide.
# Avec « inventaire », on liste les fichiers (chemin, taille) au lieu de les
# hacher : le référentiel pèse jusqu'à 2 To (DAT 6.6), et chaque fichier
# chiffré porte déjà son contrôle d'intégrité (GCM, empreinte en base).
marquer_termine() {
    local dir="$1" mode="${2:-empreintes}"
    if [[ "$mode" == inventaire ]]; then
        (cd "$dir" && find . -type f ! -name TERMINE ! -name INVENTAIRE -printf '%P %s\n' | sort > INVENTAIRE)
    else
        (cd "$dir" && find . -type f ! -name TERMINE ! -name EMPREINTES -print0 | sort -z \
            | xargs -0 -r sha256sum > EMPREINTES)
    fi
    date '+%Y-%m-%dT%H:%M:%S%z' > "$dir/TERMINE"
}

# Dernière sauvegarde complète d'un type (base, fichiers), ou rien.
derniere_terminee() {
    local racine="$1"
    [[ -d "$racine" ]] || return 0
    local d
    for d in $(ls -1 "$racine" 2>/dev/null | sort -r); do
        [[ -f "$racine/$d/TERMINE" ]] && { echo "$racine/$d"; return 0; }
    done
}

# Rétention DAT 6.5 : on garde tout ce qui a moins de RETENTION_JOURS jours,
# et au-delà la première sauvegarde de chaque mois pendant RETENTION_MOIS mois.
appliquer_retention() {
    local racine="$1"
    [[ -d "$racine" ]] || return 0
    local limite_jours limite_mois nom date_sauv mois deja=""
    limite_jours="$(date -d "-$RETENTION_JOURS days" '+%Y%m%d')"
    limite_mois="$(date -d "-$RETENTION_MOIS months" '+%Y%m%d')"
    for nom in $(ls -1 "$racine" | sort); do
        date_sauv="${nom:0:8}"
        [[ "$date_sauv" =~ ^[0-9]{8}$ ]] || continue
        mois="${date_sauv:0:6}"
        if [[ "$date_sauv" < "$limite_jours" ]]; then
            if [[ "$date_sauv" > "$limite_mois" && " $deja " != *" $mois "* && -f "$racine/$nom/TERMINE" ]]; then
                deja="$deja $mois"          # première sauvegarde complète du mois : gardée
                continue
            fi
            journal "Rétention : suppression de $racine/$nom"
            rm -rf "${racine:?}/$nom"
        elif [[ -f "$racine/$nom/TERMINE" ]]; then
            deja="$deja $mois"
        fi
    done
}

# Droits de niveau base de $PGDATABASE, en SQL rejouable par psql sur une
# base de nom quelconque (variable psql `cible`) : propriétaire, ACL de
# pg_database.datacl (dans leur ordre), réglages ALTER DATABASE / ALTER ROLE …
# IN DATABASE de pg_db_role_setting. Les réglages de liste (search_path…)
# sont repris tels quels, comme le fait pg_dump ; les autres, en littéral.
exporter_droits_base() {
    echo "-- Droits de niveau base de $PGDATABASE ($(date '+%Y-%m-%d %H:%M:%S'))."
    echo "-- Rejouer : psql -v ON_ERROR_STOP=1 -v cible=<base> -f droits-base.sql"
    psql -X -At -v ON_ERROR_STOP=1 -d "$PGDATABASE" <<'SQL' | tr -d '\r'
with d as (select oid, datacl, datdba from pg_database where datname = current_database()),
parametres as (
    select s.setrole, split_part(c.v, '=', 1) as nom, substr(c.v, strpos(c.v, '=') + 1) as valeur, c.n
    from pg_db_role_setting s join d on s.setdatabase = d.oid,
         unnest(s.setconfig) with ordinality c(v, n))
select ligne from (
    select 1 as ordre, 0::bigint as n,
           format('ALTER DATABASE :"cible" OWNER TO %I; -- proprietaire', pg_get_userbyid(datdba)) as ligne
    from d
    union all
    select 2, 0, 'REVOKE ALL ON DATABASE :"cible" FROM PUBLIC;' from d where datacl is not null
    union all
    select 3, a.n, format('GRANT %s ON DATABASE :"cible" TO %s%s;', a.privilege_type,
                          case when a.grantee = 0 then 'PUBLIC' else quote_ident(pg_get_userbyid(a.grantee)) end,
                          case when a.is_grantable then ' WITH GRANT OPTION' else '' end)
    from d, lateral (select x.*, row_number() over () as n from aclexplode(d.datacl) x) a
    where a.grantee <> d.datdba
    union all
    select 4, p.n, format('%s SET %s = %s;',
                          case when p.setrole = 0 then 'ALTER DATABASE :"cible"'
                               else format('ALTER ROLE %I IN DATABASE :"cible"', pg_get_userbyid(p.setrole)) end,
                          p.nom,
                          case when p.nom in ('search_path', 'temp_tablespaces', 'session_preload_libraries',
                                              'local_preload_libraries', 'shared_preload_libraries')
                               then p.valeur else quote_literal(p.valeur) end)
    from parametres p
) t order by ordre, n, ligne;
SQL
}
