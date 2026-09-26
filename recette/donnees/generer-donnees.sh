#!/usr/bin/env bash
# Régénère les jeux de données de recette (Java seul, décision D5 : aucun Python).
#
# Le classpath est celui du backend, résolu hors ligne par Maven : PDFBox y est déjà
# (l'application l'utilise pour l'OCR), rien n'est téléchargé.
# Usage : generer-donnees.sh [--sortie DOSSIER] [--pages-scan 20] [--police-arabe F.ttf]
#   sans option : réécrit les fichiers versionnés de ce dossier et MANIFESTE.csv
#   --pages-scan 20 --sortie genere/ : scans de 20 pages (critère de sortie E6), non versionnés

source "$(dirname "${BASH_SOURCE[0]}")/../lib/commun.sh"
ICI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND="${BACKEND:-$DEPOT_RACINE/backend}"
CP_FICHIER="$(mktemp "${TMPDIR:-/tmp}/qa-cp-donnees.XXXXXX")"; trap 'rm -f "$CP_FICHIER"' EXIT
(cd "$BACKEND" && mvn -o -B -q dependency:build-classpath -Dmdep.outputFile="$CP_FICHIER" -Dmdep.includeScope=runtime) \
  || fatal "classpath du backend introuvable hors ligne"
ARGS=("$@"); [[ " $* " == *" --sortie "* ]] || ARGS+=(--sortie "$ICI")
java_source -cp "$(cat "$CP_FICHIER")" "$ICI/GenerateurDonnees.java" "${ARGS[@]}"
