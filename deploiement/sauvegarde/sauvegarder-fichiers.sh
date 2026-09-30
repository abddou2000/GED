#!/usr/bin/env bash
# =====================================================================
#  sauvegarder-fichiers.sh — copie incrémentale du référentiel de fichiers
#  chiffrés vers un support distinct (DAT 6.5)
#
#  ORDRE IMPOSÉ : toujours APRÈS la sauvegarde de la base. Ainsi, tout fichier
#  référencé par la base sauvegardée figure dans la sauvegarde des fichiers ;
#  l'inverse (fichier plus récent que la base) ne produit qu'un orphelin
#  inoffensif, traité par rapprocher-orphelins.sh. Le script refuse de
#  tourner si aucune sauvegarde de base terminée n'est plus récente que la
#  dernière sauvegarde de fichiers.
#
#  Chaque instantané est complet mais ne coûte que les nouveaux fichiers :
#  les fichiers inchangés sont des liens physiques vers l'instantané
#  précédent (rsync --link-dest). Les fichiers étant en écriture unique, un
#  fichier n'est jamais modifié en place.
# =====================================================================
set -Eeuo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/commun-sauvegarde.sh"

SOURCE="${GED_STOCKAGE_RACINE:?GED_STOCKAGE_RACINE absente}"
[[ -d "$SOURCE" ]] || echec "référentiel introuvable : $SOURCE"

BASE="$(derniere_terminee "$DEST_BASE")"
[[ -n "$BASE" ]] || echec "aucune sauvegarde de base terminée : sauvegarder la base d'abord"
PRECEDENT="$(derniere_terminee "$DEST_FICHIERS")"
if [[ -n "$PRECEDENT" && "$(cat "$PRECEDENT/BASE_DE_REFERENCE")" == "$(basename "$BASE")" \
      && "${GED_FORCER_FICHIERS:-non}" != oui ]]; then
    echec "la dernière sauvegarde de base ($(basename "$BASE")) a déjà sa sauvegarde de fichiers : sauvegarder la base d'abord"
fi

DIR="$DEST_FICHIERS/$(horodatage)"
mkdir -p "$DIR"
chmod 0700 "$DIR"
debut=$SECONDS
journal "Sauvegarde des fichiers de $SOURCE vers $DIR (après la base $(basename "$BASE"))"

if command -v rsync >/dev/null 2>&1; then
    lien=()
    [[ -n "$PRECEDENT" ]] && lien=(--link-dest="$PRECEDENT/donnees")
    rsync -a --numeric-ids "${lien[@]}" "$SOURCE/" "$DIR/donnees/" || echec "rsync en échec"
else
    # Poste sans rsync (essai local) : copie complète, même résultat logique.
    mkdir -p "$DIR/donnees"
    cp -a "$SOURCE/." "$DIR/donnees/" || echec "copie en échec"
fi

nombre="$(find "$DIR/donnees" -type f -name '*.enc' | wc -l)"
basename "$BASE" > "$DIR/BASE_DE_REFERENCE"
{
    echo "source=$SOURCE"
    echo "fichiers=$nombre"
    echo "duree_s=$((SECONDS - debut))"
} > "$DIR/MANIFESTE"
marquer_termine "$DIR" inventaire
journal "Sauvegarde des fichiers terminée : $nombre fichiers en $((SECONDS - debut)) s"

appliquer_retention "$DEST_FICHIERS"
