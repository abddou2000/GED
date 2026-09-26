#!/usr/bin/env bash
# =====================================================================
#  test-fumee.sh — vérification post-déploiement de bout en bout (DAT 10.1) :
#  front servi et durci, connexion, dépôt, recherche. Passe par l'URL
#  PUBLIQUE, donc par NGINX, comme un utilisateur.
#
#  Usage : test-fumee.sh <url-publique> [--api-seulement]
#
#  Variables (fichier /etc/ged/deploiement.env) :
#    GED_FUMEE_IDENTIFIANT   compte technique dédié au test de fumée
#    GED_FUMEE_MDP           son mot de passe
#    GED_FUMEE_TYPE_DOCUMENT identifiant du type documentaire de test
#  Le compte n'a de droits que sur l'espace « Tests de fumée » : le document
#  déposé y reste confiné, puis part à la corbeille en fin de test.
#
#  Les appels d'API sont regroupés dans les fonctions api_* : ce sont elles
#  qu'il faut adapter quand l'authentification (E2 : annuaire, jeton de
#  renouvellement) ou les chemins du contrat d'API (E9) évoluent.
# =====================================================================
set -Eeuo pipefail

DIR_SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=commun.sh
source "$DIR_SCRIPTS/commun.sh"

URL="${1:?usage : test-fumee.sh <url-publique> [--api-seulement]}"
URL="${URL%/}"
API_SEULEMENT="${2:-}"
: "${GED_FUMEE_IDENTIFIANT:?GED_FUMEE_IDENTIFIANT absent}"
: "${GED_FUMEE_MDP:?GED_FUMEE_MDP absent}"
: "${GED_FUMEE_TYPE_DOCUMENT:?GED_FUMEE_TYPE_DOCUMENT absent}"
exiger_commandes curl jq

TRAVAIL="$(mktemp -d)"
trap 'rm -rf "$TRAVAIL"' EXIT
MARQUE="FUMEE-$(horodatage)-$RANDOM"
JETON=""

controle() {
    journal "  [OK] $*"
}

# ---------------------------------------------------------------------
# Front : paquet servi, en-têtes de sécurité, config.json de production
# ---------------------------------------------------------------------
verifier_front() {
    local entetes="$TRAVAIL/entetes"
    curl -fsS --max-time 10 -D "$entetes" -o /dev/null "$URL/" || echec "page d'accueil injoignable"
    controle "page d'accueil servie"
    grep -qi '^content-security-policy:' "$entetes" || echec "en-tête Content-Security-Policy absent"
    grep -qi '^x-content-type-options: *nosniff' "$entetes" || echec "en-tête X-Content-Type-Options absent"
    if [[ "$URL" == https://* ]]; then
        grep -qi '^strict-transport-security:' "$entetes" || echec "en-tête HSTS absent"
    fi
    if grep -qi '^server: .*[0-9]' "$entetes"; then
        echec "la version du serveur est divulguée (server_tokens)"
    fi
    controle "en-têtes de sécurité présents"
    curl -fsS --max-time 10 "$URL/assets/config.json" | jq -e '.demo == false' >/dev/null \
        || echec "config.json absent ou en mode démonstration"
    controle "config.json de production"
}

# ---------------------------------------------------------------------
# API — à adapter avec E2 (authentification) et E9 (contrat d'API)
# ---------------------------------------------------------------------
api_connexion() {
    local corps
    corps="$(jq -n --arg e "$GED_FUMEE_IDENTIFIANT" --arg m "$GED_FUMEE_MDP" '{email: $e, motDePasse: $m}')"
    JETON="$(curl -fsS --max-time 20 -H 'Content-Type: application/json' -d "$corps" \
        "$URL/api/v1/auth/login" | jq -r '.token // empty')" || true
    [[ -n "$JETON" ]] || echec "connexion du compte de fumée refusée"
    controle "connexion"
}

api_depot() {
    local pdf="$TRAVAIL/$MARQUE.pdf" reponse
    # Plus petit PDF valide : une page vide, avec le marqueur dans les métadonnées.
    printf '%%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\n4 0 obj<</Title(%s)>>endobj\ntrailer<</Root 1 0 R/Info 4 0 R>>\n%%%%EOF\n' "$MARQUE" > "$pdf"
    reponse="$(curl -fsS --max-time 60 -H "Authorization: Bearer $JETON" \
        -F "file=@$pdf;type=application/pdf" -F "name=$MARQUE" \
        -F "typeDocumentId=$GED_FUMEE_TYPE_DOCUMENT" "$URL/api/v1/documents")" \
        || echec "dépôt refusé"
    DOCUMENT_ID="$(jq -r '.id // empty' <<<"$reponse")"
    [[ -n "$DOCUMENT_ID" ]] || echec "dépôt sans identifiant en réponse"
    controle "dépôt (document $DOCUMENT_ID)"
}

api_recherche() {
    local trouve
    trouve="$(curl -fsS --max-time 20 -G -H "Authorization: Bearer $JETON" \
        --data-urlencode "search=$MARQUE" "$URL/api/v1/documents" \
        | jq -r --arg id "$DOCUMENT_ID" '[.content[]? | select((.id|tostring) == $id)] | length')" \
        || echec "recherche en erreur"
    [[ "$trouve" == "1" ]] || echec "le document déposé est introuvable par la recherche"
    controle "recherche"
}

api_nettoyage() {
    # Mise à la corbeille : la purge définitive reste une décision humaine.
    curl -fsS --max-time 20 -X DELETE -H "Authorization: Bearer $JETON" \
        "$URL/api/v1/documents/$DOCUMENT_ID" -o /dev/null \
        || journal "  [ATTENTION] document de fumée $DOCUMENT_ID non mis à la corbeille"
}

journal "Test de fumée sur $URL"
[[ "$API_SEULEMENT" == "--api-seulement" ]] || verifier_front
api_connexion
api_depot
api_recherche
api_nettoyage
journal "Test de fumée réussi"
