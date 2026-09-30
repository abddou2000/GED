#!/usr/bin/env bash
# =====================================================================
#  sauvegarder-base.sh — sauvegarde de la base PostgreSQL (DAT 6.5)
#
#  Usage : sauvegarder-base.sh [--physique] [--logique] [--etiquette NOM]
#    --physique  sauvegarde de base (pg_basebackup) : point de départ de la
#                restauration à un instant donné, avec les WAL archivés en
#                continu (archiver-wal.sh). RPO 15 minutes.
#    --logique   export pg_dump au format custom : restauration sélective,
#                portable d'une version de PostgreSQL à l'autre, et
#                sauvegarde préalable à chaque déploiement.
#    (sans option : les deux, ce que fait la sauvegarde quotidienne)
#
#  Résultat : $GED_SAUVEGARDE_DESTINATION/base/<horodatage>[-etiquette]/,
#  marqué TERMINE seulement une fois l'archive relue avec succès.
# =====================================================================
set -Eeuo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/commun-sauvegarde.sh"

PHYSIQUE=non; LOGIQUE=non; ETIQUETTE=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --physique) PHYSIQUE=oui; shift ;;
        --logique) LOGIQUE=oui; shift ;;
        --etiquette) ETIQUETTE="-$2"; shift 2 ;;
        *) echec "option inconnue : $1" ;;
    esac
done
[[ "$PHYSIQUE" == non && "$LOGIQUE" == non ]] && { PHYSIQUE=oui; LOGIQUE=oui; }
: "${PGDATABASE:?PGDATABASE absente}"

DIR="$DEST_BASE/$(horodatage)$ETIQUETTE"
mkdir -p "$DIR"
chmod 0700 "$DIR"
debut=$SECONDS
journal "Sauvegarde de la base $PGDATABASE vers $DIR"

if [[ "$LOGIQUE" == oui ]]; then
    exiger_commandes pg_dump pg_restore
    pg_dump --format=custom --compress=6 --file="$DIR/$PGDATABASE.dump" "$PGDATABASE" \
        || echec "pg_dump en échec"
    # Relecture de l'archive : un export illisible découvert le jour de la
    # restauration ne vaut rien.
    pg_restore --list "$DIR/$PGDATABASE.dump" > "$DIR/$PGDATABASE.contenu" \
        || echec "archive $DIR/$PGDATABASE.dump illisible"
    journal "Export logique : $(du -h "$DIR/$PGDATABASE.dump" | cut -f1), $(grep -c ' TABLE DATA ' "$DIR/$PGDATABASE.contenu") tables"
    # Droits de niveau base (CONNECT, TEMPORARY… de pg_database.datacl) et
    # réglages ALTER ROLE … IN DATABASE : pg_dump sans --create ne les porte
    # pas. restaurer.sh les rejoue sur la base restaurée (ANO-E10-006).
    exporter_droits_base > "$DIR/droits-base.sql" || echec "export des droits de niveau base en échec"
    journal "Droits de niveau base : $(grep -c '^\(GRANT\|ALTER ROLE\|ALTER DATABASE\)' "$DIR/droits-base.sql") instruction(s)"
fi

if [[ "$PHYSIQUE" == oui ]]; then
    exiger_commandes pg_basebackup
    # -X stream : les WAL nécessaires à la cohérence de cette sauvegarde y
    # sont inclus ; elle se restaure seule, les WAL archivés servant ensuite
    # à avancer jusqu'à l'instant voulu.
    pg_basebackup --pgdata="$DIR/physique" --format=tar --gzip --wal-method=stream \
        --checkpoint=fast --label="ged-$(basename "$DIR")" --progress --verbose \
        || echec "pg_basebackup en échec"
    # Le manifeste (sommes de contrôle de chaque fichier) sert à
    # pg_verifybackup une fois l'archive extraite, lors de la restauration.
    [[ -s "$DIR/physique/backup_manifest" ]] || echec "manifeste de sauvegarde absent"
    journal "Sauvegarde physique : $(du -sh "$DIR/physique" | cut -f1)"
fi

{
    echo "base=$PGDATABASE"
    echo "serveur=${PGHOST:-local}:${PGPORT:-5432}"
    echo "logique=$LOGIQUE"
    echo "physique=$PHYSIQUE"
    echo "duree_s=$((SECONDS - debut))"
} > "$DIR/MANIFESTE"
marquer_termine "$DIR"
journal "Sauvegarde de la base terminée en $((SECONDS - debut)) s"

# Les sauvegardes étiquetées (avant déploiement) suivent la même rétention.
appliquer_retention "$DEST_BASE"
