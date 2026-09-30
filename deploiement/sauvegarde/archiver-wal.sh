#!/usr/bin/env bash
# =====================================================================
#  archiver-wal.sh — archivage continu des journaux WAL (DAT 6.5, RPO 15 min)
#
#  Appelé par PostgreSQL sur le SERVEUR DE BASE (postgresql-archivage.conf) :
#    archive_command = '/opt/ged/deploiement/sauvegarde/archiver-wal.sh %p %f'
#  Destination : $GED_WAL_ARCHIVE (support distinct du serveur), défaut
#  /mnt/sauvegarde-ged/wal.
#
#  Contrat PostgreSQL : code 0 uniquement si le segment est durablement
#  archivé ; tout autre code fait conserver le segment et réessayer. Un
#  segment déjà archivé n'est JAMAIS écrasé (sauf contenu identique) : écraser
#  une archive valide par un segment différent corromprait la chaîne.
# =====================================================================
set -Eeuo pipefail

SOURCE="${1:?chemin du segment (%p) attendu}"
NOM="${2:?nom du segment (%f) attendu}"
# Destination : 3e argument, sinon GED_WAL_ARCHIVE, sinon le support par défaut.
ARCHIVE="${3:-${GED_WAL_ARCHIVE:-/mnt/sauvegarde-ged/wal}}"
CIBLE="$ARCHIVE/$NOM"

[[ -d "$ARCHIVE" ]] || { echo "archiver-wal : $ARCHIVE absent (support non monté ?)" >&2; exit 1; }

if [[ -f "$CIBLE" ]]; then
    # Relance après un arrêt brutal : le segment peut déjà être là.
    cmp -s "$SOURCE" "$CIBLE" && exit 0
    echo "archiver-wal : $NOM existe déjà avec un contenu différent" >&2
    exit 1
fi

# Copie dans un fichier temporaire, synchronisation, puis renommage : le nom
# définitif n'apparaît que pour un segment complet.
cp "$SOURCE" "$CIBLE.partiel"
sync "$CIBLE.partiel" 2>/dev/null || sync
mv "$CIBLE.partiel" "$CIBLE"
