#!/usr/bin/env bash
# =====================================================================
#  rapprocher-orphelins.sh — rapprochement base / référentiel de fichiers
#  après une restauration (DAT 6.5, « Cohérence entre la base et les fichiers »)
#
#  Usage : rapprocher-orphelins.sh --base NOM --racine DIR
#                                  [--appliquer] [--quarantaine DIR]
#                                  [--purger-apres-jours 7] [--rapport FICHIER]
#                                  [--age-minimal-minutes 60]
#
#  - ORPHELIN : fichier présent sur le disque dont la clé est inconnue de la
#    base (déposé après la sauvegarde de base restaurée). Inoffensif. Avec
#    --appliquer, il est déplacé en quarantaine ; il n'est supprimé qu'après
#    7 jours (--purger-apres-jours), le temps de s'assurer qu'il n'est pas le
#    fruit d'une erreur de restauration. Un fichier modifié depuis moins de
#    --age-minimal-minutes (60 par défaut) n'est jamais déplacé : il peut être
#    en cours de dépôt (fichier publié, clé pas encore validée) — ligne RECENT.
#  - MANQUANT : fichier référencé par un document (version, copie de
#    conservation, archive d'export…) mais absent du disque. Signalé
#    « à ré-importer » dans le rapport ; rien n'est modifié en base. Les clés
#    du cache d'aperçus (même table cle_fichier, référentiel distinct, non
#    sauvegardé) ne sont référencées par aucune table : elles ne sont pas
#    signalées.
#  - MAL_RANGE : fichier .enc hors de son emplacement aa/bb/<id>.enc ;
#    l'application ne le trouvera pas. Signalé ; déplacé en quarantaine s'il
#    est aussi orphelin, laissé en place sinon.
#
#  Sans --appliquer ni --purger-apres-jours, le script ne fait qu'établir le
#  rapport. Avec l'un ou l'autre, il REFUSE de s'exécuter tant que
#  l'application tourne (service ged-backend actif, ou session ouverte en base
#  par le rôle applicatif) : RESTAURATION.md, étape 5, avant l'étape 6.
#  La purge recontrôle la base fichier par fichier : un fichier de quarantaine
#  dont la clé est (re)devenue connue est remis à sa place, jamais détruit.
#
#  Variables : GED_SCHEMA (ged), GED_ROLE_APPLICATION (ged_app),
#  GED_SERVICE_APPLICATION (ged-backend), GED_SQL_FICHIERS_REFERENCES (requête
#  des fichiers attendus sur le disque ; par défaut, les colonnes qui
#  référencent cle_fichier par clé étrangère, découvertes dans le catalogue).
# =====================================================================
set -Eeuo pipefail
DIR_SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")/../scripts" && pwd)"
source "$DIR_SCRIPTS/commun.sh"

BASE=""; RACINE=""; APPLIQUER=non; QUARANTAINE=""; PURGE_JOURS=""; RAPPORT=""; AGE_MIN=60
while [[ $# -gt 0 ]]; do
    case "$1" in
        --base) BASE="$2"; shift 2 ;;
        --racine) RACINE="$2"; shift 2 ;;
        --appliquer) APPLIQUER=oui; shift ;;
        --quarantaine) QUARANTAINE="$2"; shift 2 ;;
        --purger-apres-jours) PURGE_JOURS="$2"; shift 2 ;;
        --rapport) RAPPORT="$2"; shift 2 ;;
        --age-minimal-minutes) AGE_MIN="$2"; shift 2 ;;
        *) echec "option inconnue : $1" ;;
    esac
done
[[ -n "$BASE" && -n "$RACINE" ]] || echec "usage : --base NOM --racine DIR"
[[ -d "$RACINE" ]] || echec "référentiel introuvable : $RACINE"
[[ "$AGE_MIN" =~ ^[0-9]+$ ]] || echec "--age-minimal-minutes attend un nombre de minutes"
[[ -z "$PURGE_JOURS" || "$PURGE_JOURS" =~ ^[0-9]+$ ]] || echec "--purger-apres-jours attend un nombre de jours"
exiger_commandes psql comm find sort awk
RACINE="$(cd "$RACINE" && pwd)"
QUARANTAINE="${QUARANTAINE:-$(dirname "$RACINE")/quarantaine-orphelins}"
RAPPORT="${RAPPORT:-rapprochement-$(horodatage).txt}"
SCHEMA="${GED_SCHEMA:-ged}"
[[ "$SCHEMA" =~ ^[a-z_][a-z0-9_]*$ ]] || echec "schéma invalide : $SCHEMA"
ROLE_APP="${GED_ROLE_APPLICATION:-ged_app}"
SERVICE_APP="${GED_SERVICE_APPLICATION:-ged-backend}"

TRAVAIL="$(mktemp -d)"
trap 'rm -rf "$TRAVAIL"' EXIT

requete() { psql -X -d "$BASE" -At -v ON_ERROR_STOP=1 -c "$1" | tr -d '\r'; }

# Toutes les clés connues (fichiers de documents ET aperçus en cache) : un
# fichier dont la clé existe n'est jamais un orphelin.
cles_connues() { requete "select id::text from $SCHEMA.cle_fichier" | sort -u; }

# ---------------------------------------------------------------------
# Garde : aucune action destructrice tant que l'application tourne.
# ---------------------------------------------------------------------
exiger_application_arretee() {
    if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet "$SERVICE_APP" 2>/dev/null; then
        echec "le service $SERVICE_APP est actif : arrêter l'application avant --appliquer ou la purge (RESTAURATION.md, étape 5)"
    fi
    local sessions
    sessions="$(requete "select count(*) from pg_stat_activity where datname = current_database() and usename = '$ROLE_APP'")"
    [[ "$sessions" == 0 ]] || echec "$sessions session(s) du rôle applicatif $ROLE_APP ouverte(s) sur $BASE : l'application tourne, refus de déplacer ou de supprimer des fichiers"
}
if [[ "$APPLIQUER" == oui || -n "$PURGE_JOURS" ]]; then
    exiger_application_arretee
fi

# ---------------------------------------------------------------------
# Inventaires : base (connues, attendues) et disque (id, chemin, âge).
# ---------------------------------------------------------------------
cles_connues > "$TRAVAIL/connues"
if [[ -n "${GED_SQL_FICHIERS_REFERENCES:-}" ]]; then
    SQL_ATTENDUS="$GED_SQL_FICHIERS_REFERENCES"
else
    SQL_ATTENDUS="$(requete "
        select string_agg(format('select %I::text from %I.%I where %I is not null',
                                 a.attname, n.nspname, t.relname, a.attname), ' union ')
        from pg_constraint c
        join pg_class t on t.oid = c.conrelid
        join pg_namespace n on n.oid = t.relnamespace
        join pg_attribute a on a.attrelid = c.conrelid and a.attnum = c.conkey[1]
        where c.contype = 'f' and c.confrelid = '$SCHEMA.cle_fichier'::regclass
          and cardinality(c.conkey) = 1")"
    [[ -n "$SQL_ATTENDUS" ]] || echec "aucune table ne référence $SCHEMA.cle_fichier : préciser GED_SQL_FICHIERS_REFERENCES"
fi
requete "$SQL_ATTENDUS" | sort -u > "$TRAVAIL/attendus"

# id<TAB>chemin relatif<TAB>minutes depuis la dernière modification
maintenant="$(date +%s)"
find "$RACINE" -type f -name '*.enc' -printf '%P\t%T@\n' \
    | awk -F'\t' -v m="$maintenant" '{ n = $1; sub(/.*\//, "", n); sub(/\.enc$/, "", n);
                                       printf "%s\t%s\t%d\n", n, $1, (m - $2) / 60 }' \
    | sort -t$'\t' -k1,1 > "$TRAVAIL/disque-detail"
cut -f1 "$TRAVAIL/disque-detail" | sort -u > "$TRAVAIL/disque"
comm -23 "$TRAVAIL/disque" "$TRAVAIL/connues" > "$TRAVAIL/orphelins"
comm -13 "$TRAVAIL/disque" "$TRAVAIL/attendus" > "$TRAVAIL/manquants"

chemin_de() {   # même règle que le stockage : aa/bb/<id>.enc
    echo "${1:0:2}/${1:2:2}/$1.enc"
}

# Orphelins à déplacer (assez anciens) et orphelins récents (laissés en place).
awk -F'\t' 'NR == FNR { o[$1] = 1; next } ($1 in o)' "$TRAVAIL/orphelins" "$TRAVAIL/disque-detail" \
    | awk -F'\t' -v a="$AGE_MIN" -v dq="$TRAVAIL/a-deplacer" -v rc="$TRAVAIL/recents" \
          '{ if ($3 >= a) print $2 > dq; else print $2 > rc }'
touch "$TRAVAIL/a-deplacer" "$TRAVAIL/recents"
awk -F'\t' '{ attendu = substr($1, 1, 2) "/" substr($1, 3, 2) "/" $1 ".enc"; if ($2 != attendu) print $2 }' \
    "$TRAVAIL/disque-detail" > "$TRAVAIL/mal-ranges"

{
    echo "Rapprochement du $(date '+%Y-%m-%d %H:%M:%S')"
    echo "Base : $BASE — référentiel : $RACINE"
    echo "Clés connues de la base         : $(wc -l < "$TRAVAIL/connues")"
    echo "Fichiers référencés (documents) : $(wc -l < "$TRAVAIL/attendus")"
    echo "Fichiers présents sur le disque : $(wc -l < "$TRAVAIL/disque")"
    echo "Orphelins (disque sans base)    : $(wc -l < "$TRAVAIL/orphelins")"
    echo "  dont récents (< $AGE_MIN min)     : $(wc -l < "$TRAVAIL/recents")"
    echo "Manquants (base sans disque)    : $(wc -l < "$TRAVAIL/manquants")"
    echo "Mal rangés                      : $(wc -l < "$TRAVAIL/mal-ranges")"
    echo
    echo "--- Documents à ré-importer (fichier manquant) ---"
    sed 's/^/A_REIMPORTER /' "$TRAVAIL/manquants"
    echo
    echo "--- Orphelins ---"
    sed 's/^/ORPHELIN /' "$TRAVAIL/a-deplacer"
    sed 's/^/RECENT /' "$TRAVAIL/recents"
    echo
    echo "--- Fichiers mal rangés (hors aa/bb/<id>.enc) ---"
    sed 's/^/MAL_RANGE /' "$TRAVAIL/mal-ranges"
} > "$RAPPORT"

if [[ "$APPLIQUER" == oui && -s "$TRAVAIL/a-deplacer" ]]; then
    lot="$QUARANTAINE/$(horodatage)"
    while IFS= read -r rel; do
        mkdir -p "$lot/$(dirname "$rel")"
        mv -n "$RACINE/$rel" "$lot/$rel" || echec "déplacement en quarantaine impossible : $RACINE/$rel → $lot/$rel"
    done < "$TRAVAIL/a-deplacer"
    journal "$(wc -l < "$TRAVAIL/a-deplacer") orphelin(s) mis en quarantaine dans $lot"
fi

# ---------------------------------------------------------------------
# Purge des lots de quarantaine trop anciens, avec recontrôle en base
# juste avant : un fichier dont la clé est connue retourne à sa place.
# ---------------------------------------------------------------------
if [[ -n "$PURGE_JOURS" && -d "$QUARANTAINE" ]]; then
    while IFS= read -r -d '' lot; do
        cles_connues > "$TRAVAIL/connues-purge"
        while IFS= read -r -d '' f; do
            rel="${f#"$lot"/}"
            id="$(basename "$f" .enc)"
            if grep -qxF -- "$id" "$TRAVAIL/connues-purge"; then
                dest="$RACINE/$(chemin_de "$id")"
                if [[ -e "$dest" ]]; then
                    echec "purge interrompue : $f est référencé en base et $dest existe déjà ; à trancher à la main"
                fi
                mkdir -p "$(dirname "$dest")"
                mv -n "$f" "$dest" || echec "remise en place impossible : $f → $dest"
                journal "Quarantaine : $rel référencé en base, remis en place ($dest)"
            else
                rm -f -- "$f"
            fi
        done < <(find "$lot" -type f -print0)
        rm -rf -- "$lot"
        journal "Quarantaine purgée : $lot"
    done < <(find "$QUARANTAINE" -mindepth 1 -maxdepth 1 -type d -mtime +"$PURGE_JOURS" -print0)
fi

journal "Rapport de rapprochement : $RAPPORT ($(wc -l < "$TRAVAIL/orphelins") orphelin(s) dont $(wc -l < "$TRAVAIL/recents") récent(s), $(wc -l < "$TRAVAIL/manquants") manquant(s), $(wc -l < "$TRAVAIL/mal-ranges") mal rangé(s))"
