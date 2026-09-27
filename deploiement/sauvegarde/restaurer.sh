#!/usr/bin/env bash
# =====================================================================
#  restaurer.sh — restauration pas à pas (DAT 6.5). Procédure complète et
#  ordre des étapes : docs/exploitation/RESTAURATION.md.
#
#  Étapes (une commande chacune, dans cet ordre) :
#    base-logique  --sauvegarde DIR --cible NOM [--sans-proprietaires]
#        Recrée la base NOM (qui ne doit pas exister) depuis l'export pg_dump.
#    base-physique --sauvegarde DIR --pgdata DIR [--instant "AAAA-MM-JJ HH:MM:SS+00"] [--wal DIR]
#        Prépare un répertoire de données vide pour une restauration à un
#        instant donné (sauvegarde de base + WAL archivés), puis indique la
#        commande de démarrage. Sans --instant : jusqu'au dernier WAL archivé.
#    fichiers      --sauvegarde-base DIR --cible DIR
#        Restaure l'instantané de fichiers pris JUSTE APRÈS cette sauvegarde
#        de base (cohérence base/fichiers).
#    cles          --archive FICHIER.tar.gpg --cible DIR
#        Déchiffre l'archive des clés (clé privée GPG du responsable sécurité
#        requise, sur le poste qui restaure) et contrôle ses empreintes.
#  Puis : rapprocher-orphelins.sh, et la vérification d'intégrité de
#  l'application sur un échantillon (RESTAURATION.md, étape 6).
# =====================================================================
set -Eeuo pipefail
DIR_SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")/../scripts" && pwd)"
source "$DIR_SCRIPTS/commun.sh"

ETAPE="${1:-}"; shift || true
SAUVEGARDE=""; CIBLE=""; PGDATA_CIBLE=""; INSTANT=""; SANS_PROPRIETAIRES=non; ARCHIVE=""; WAL=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --sauvegarde|--sauvegarde-base) SAUVEGARDE="$2"; shift 2 ;;
        --cible) CIBLE="$2"; shift 2 ;;
        --pgdata) PGDATA_CIBLE="$2"; shift 2 ;;
        --instant) INSTANT="$2"; shift 2 ;;
        --sans-proprietaires) SANS_PROPRIETAIRES=oui; shift ;;
        --archive) ARCHIVE="$2"; shift 2 ;;
        --wal) WAL="$2"; shift 2 ;;
        *) echec "option inconnue : $1" ;;
    esac
done

exiger_sauvegarde_terminee() {
    [[ -f "$1/TERMINE" ]] || echec "$1 n'est pas une sauvegarde terminée (marqueur TERMINE absent)"
}

# Contrôle des empreintes enregistrées à la sauvegarde : on ne restaure pas
# une archive altérée sur son support.
verifier_empreintes() {
    local dir="$1"
    [[ -f "$dir/EMPREINTES" ]] || return 0
    (cd "$dir" && sha256sum --quiet -c EMPREINTES) || echec "empreintes invalides dans $dir : support altéré"
    journal "Empreintes de $dir vérifiées"
}

case "$ETAPE" in
base-logique)
    [[ -n "$SAUVEGARDE" && -n "$CIBLE" ]] || echec "--sauvegarde et --cible obligatoires"
    exiger_sauvegarde_terminee "$SAUVEGARDE"
    verifier_empreintes "$SAUVEGARDE"
    exiger_commandes psql pg_restore createdb
    dump="$(ls "$SAUVEGARDE"/*.dump 2>/dev/null | head -1)"
    [[ -n "$dump" ]] || echec "aucun export logique dans $SAUVEGARDE"
    if [[ "$(psql -XAt -d postgres -c "select 1 from pg_database where datname = '$CIBLE'")" == 1 ]]; then
        echec "la base $CIBLE existe déjà : on ne restaure jamais par-dessus une base existante"
    fi
    options=(--exit-on-error --jobs=4)
    # Hors du serveur d'origine (poste d'essai), les rôles ged_owner/ged_app
    # peuvent ne pas exister : --sans-proprietaires restaure sans eux.
    [[ "$SANS_PROPRIETAIRES" == oui ]] && options+=(--no-owner --no-privileges)
    debut=$SECONDS
    createdb "$CIBLE"
    pg_restore "${options[@]}" --dbname="$CIBLE" "$dump" || echec "pg_restore en échec"
    journal "Base $CIBLE restaurée depuis $(basename "$dump") en $((SECONDS - debut)) s"
    ;;

base-physique)
    [[ -n "$SAUVEGARDE" && -n "$PGDATA_CIBLE" ]] || echec "--sauvegarde et --pgdata obligatoires"
    exiger_sauvegarde_terminee "$SAUVEGARDE"
    verifier_empreintes "$SAUVEGARDE"
    [[ -d "$SAUVEGARDE/physique" ]] || echec "pas de sauvegarde physique dans $SAUVEGARDE"
    [[ ! -e "$PGDATA_CIBLE" || -z "$(ls -A "$PGDATA_CIBLE")" ]] || echec "$PGDATA_CIBLE doit être vide"
    mkdir -p "$PGDATA_CIBLE"
    chmod 0700 "$PGDATA_CIBLE"
    tar -xzf "$SAUVEGARDE/physique/base.tar.gz" -C "$PGDATA_CIBLE"
    tar -xzf "$SAUVEGARDE/physique/pg_wal.tar.gz" -C "$PGDATA_CIBLE/pg_wal"
    cp "$SAUVEGARDE/physique/backup_manifest" "$PGDATA_CIBLE/"
    if command -v pg_verifybackup >/dev/null 2>&1; then
        pg_verifybackup --no-parse-wal "$PGDATA_CIBLE" || echec "pg_verifybackup : sauvegarde physique altérée"
    fi
    archive="${WAL:-${GED_WAL_ARCHIVE:-/mnt/sauvegarde-ged/wal}}"
    {
        echo "# Restauration à un instant donné (restaurer.sh, $(date '+%Y-%m-%d %H:%M'))"
        echo "restore_command = 'cp \"$archive/%f\" \"%p\"'"
        if [[ -n "$INSTANT" ]]; then
            echo "recovery_target_time = '$INSTANT'"
            echo "recovery_target_action = 'promote'"
        fi
        # Aucun archivage depuis l'instance restaurée : elle écrirait dans la
        # chaîne WAL de l'instance d'origine.
        echo "archive_mode = off"
    } >> "$PGDATA_CIBLE/postgresql.auto.conf"
    touch "$PGDATA_CIBLE/recovery.signal"
    journal "Répertoire $PGDATA_CIBLE prêt. Démarrer : pg_ctl -D $PGDATA_CIBLE start ; suivre le journal jusqu'à « database system is ready »."
    ;;

fichiers)
    [[ -n "$SAUVEGARDE" && -n "$CIBLE" ]] || echec "--sauvegarde-base et --cible obligatoires"
    racine_fichiers="$(dirname "$(dirname "$SAUVEGARDE")")/fichiers"
    nom_base="$(basename "$SAUVEGARDE")"
    instantane=""
    for d in $(ls -1 "$racine_fichiers" | sort); do
        if [[ -f "$racine_fichiers/$d/TERMINE" && "$(cat "$racine_fichiers/$d/BASE_DE_REFERENCE")" == "$nom_base" ]]; then
            instantane="$racine_fichiers/$d"; break
        fi
    done
    [[ -n "$instantane" ]] || echec "aucun instantané de fichiers pris après la sauvegarde de base $nom_base"
    [[ ! -e "$CIBLE" || -z "$(ls -A "$CIBLE")" ]] || echec "$CIBLE doit être vide"
    mkdir -p "$CIBLE"
    debut=$SECONDS
    cp -a "$instantane/donnees/." "$CIBLE/"
    # Contrôle de complétude contre l'inventaire de la sauvegarde.
    attendu="$(grep -c '^donnees/' "$instantane/INVENTAIRE")"
    obtenu="$(find "$CIBLE" -type f | wc -l)"
    [[ "$attendu" == "$obtenu" ]] || echec "restauration incomplète : $obtenu fichiers sur $attendu"
    journal "Fichiers restaurés depuis $(basename "$instantane") : $obtenu fichiers en $((SECONDS - debut)) s"
    ;;

cles)
    [[ -n "$ARCHIVE" && -n "$CIBLE" ]] || echec "--archive et --cible obligatoires"
    exiger_commandes gpg tar sha256sum
    if [[ -f "$ARCHIVE.sha256" ]]; then
        (cd "$(dirname "$ARCHIVE")" && sha256sum --quiet -c "$(basename "$ARCHIVE").sha256") \
            || echec "archive des clés altérée"
    fi
    mkdir -p "$CIBLE"
    chmod 0700 "$CIBLE"
    gpg --batch --yes --decrypt "$ARCHIVE" | tar -xf - -C "$CIBLE" \
        || echec "déchiffrement impossible : clé privée du responsable sécurité absente de ce poste ?"
    (cd "$CIBLE/cles" && sha256sum --quiet -c EMPREINTES) || echec "contenu de l'archive des clés altéré"
    journal "Clés restaurées dans $CIBLE/cles : $(ls "$CIBLE/cles" | tr '\n' ' ')"
    ;;

*)
    sed -n '4,20p' "$0" | sed 's/^#  \{0,1\}//'
    exit 2
    ;;
esac
