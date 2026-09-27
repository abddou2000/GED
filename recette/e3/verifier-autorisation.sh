#!/usr/bin/env bash
# Recette E3 — autorisation et confidentialité par l'API (voir RecetteAutorisation.java).
# Construit son propre jeu d'habilitations par l'API d'administration, dépose des témoins
# PUBLIC / PRIVE / CONFIDENTIEL, puis vérifie liste, recherche, totaux, tableau de bord, arbre,
# 404 indiscernable sur 14 routes, désignation, rupture d'héritage, rattachement.
#
# Usage : GED_URL=… GED_RECETTE_MOT_DE_PASSE=… verifier-autorisation.sh [--reinitialiser]
# Comptes et nœuds : variables GED_E3_* (en-tête de RecetteAutorisation.java).
# Prérequis : JDK 17, Maven hors ligne (classpath du backend, pour Jackson).

source "$(dirname "${BASH_SOURCE[0]}")/../lib/commun.sh"
ICI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND="${BACKEND:-$DEPOT_RACINE/backend}"
CP_FICHIER="$(mktemp "${TMPDIR:-/tmp}/qa-cp-e3.XXXXXX")"; trap 'rm -f "$CP_FICHIER"' EXIT
(cd "$BACKEND" && mvn -o -B -q dependency:build-classpath -Dmdep.outputFile="$CP_FICHIER" -Dmdep.includeScope=runtime) \
  || fatal "classpath du backend introuvable hors ligne"
export GED_E3_FICHIER="${GED_E3_FICHIER:-$RECETTE_RACINE/donnees/pdf_texte_fr_facture.pdf}"
if command -v cygpath >/dev/null 2>&1; then GED_E3_FICHIER="$(cygpath -m "$GED_E3_FICHIER")"; fi
java_source -cp "$(cat "$CP_FICHIER")" "$ICI/RecetteAutorisation.java" "$@"
