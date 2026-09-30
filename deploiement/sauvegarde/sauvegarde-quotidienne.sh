#!/usr/bin/env bash
# =====================================================================
#  sauvegarde-quotidienne.sh — enchaînement quotidien (DAT 6.5), dans
#  l'ordre imposé : base, PUIS fichiers, PUIS clés. Une étape en échec
#  arrête la suite : une sauvegarde de fichiers sans base récente casserait
#  la cohérence recherchée. Lancé par ged-sauvegarde.timer.
# =====================================================================
set -Eeuo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$DIR/../scripts/commun.sh"
GED_JOURNAL="${GED_JOURNAL:-/var/log/ged/sauvegardes.log}"

journal "=== Sauvegarde quotidienne ==="
"$DIR/sauvegarder-base.sh"
"$DIR/sauvegarder-fichiers.sh"
"$DIR/sauvegarder-cles.sh"
journal "=== Sauvegarde quotidienne terminée ==="
