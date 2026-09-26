#!/usr/bin/env bash
# Test de fumée HTTP de la GED (DAT V3 §10.1 : « vérifications post-déploiement :
# sondes de santé et test de fumée (connexion, dépôt, recherche) »).
#
# Enchaîne, contre une instance déployée : santé → connexion → identité → dépôt →
# recherche → téléchargement (octets identiques) → [suppression douce]. S'arrête à la
# première étape en échec (les suivantes n'ont plus de sens) et sort en code 1, ce qui
# permet au script de déploiement de déclencher son retour arrière.
#
# Usage : fumee.sh --url https://ged-uat.exemple [options]
#   --url URL            URL de base (ou variable GED_URL)
#   --fichier CHEMIN     document déposé (défaut : recette/donnees/pdf_texte_fr_convention.pdf)
#   --nettoyer           supprime (suppression douce) le document déposé en fin de test
#   --junit FICHIER      écrit aussi un rapport JUnit XML (intégration continue)
#   --delai-sante N      secondes d'attente maximale de la sonde de santé (défaut : 120)
# Identifiants : GED_RECETTE_IDENTIFIANT et GED_RECETTE_MOT_DE_PASSE (compte de test
# dédié, jamais un compte nominatif), ou GED_RECETTE_JETON, ou GED_RECETTE_CLE_API.
# Contrat d'API paramétrable : voir recette/lib/api.sh. GED_EXIGER_UUID=1 : l'identifiant
# renvoyé au dépôt doit être un UUID (à activer dès l'intégration de E1).
# Dépendances : bash 4, curl, sha256sum. Aucune (ni jq, ni Python).

source "$(dirname "${BASH_SOURCE[0]}")/../lib/commun.sh"

FICHIER="$RECETTE_RACINE/donnees/pdf_texte_fr_convention.pdf"; NETTOYER=0; JUNIT=""; DELAI_SANTE=120
while [[ $# -gt 0 ]]; do
  case "$1" in
    --url) export GED_URL="$2"; shift 2 ;;
    --fichier) FICHIER="$2"; shift 2 ;;
    --nettoyer) NETTOYER=1; shift ;;
    --junit) JUNIT="$2"; shift 2 ;;
    --delai-sante) DELAI_SANTE="$2"; shift 2 ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) fatal "option inconnue : $1" ;;
  esac
done
[[ -n "${GED_URL:-}" ]] || fatal "URL de base manquante (--url ou GED_URL)"
[[ -f "$FICHIER" ]] || fatal "fichier de dépôt introuvable : $FICHIER"
command -v curl >/dev/null || fatal "curl introuvable"
source "$RECETTE_RACINE/lib/api.sh"
trap api_nettoyer_tmp EXIT

JUNIT_CAS=(); DEBUT_TOTAL=$(date +%s)
etape() {  # etape <id> <OK|ECHEC> <libellé> [détail] ; en ECHEC, termine le test
  local id="$1" statut="$2" lib="$3" det="${4:-}"
  resultat "$id" "$statut" "$lib" "$det"
  local echappe
  echappe="$(printf '%s' "$det" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' -e 's/"/\&quot;/g')"
  if [[ "$statut" == ECHEC ]]; then JUNIT_CAS+=("<testcase classname=\"fumee\" name=\"$id $lib\"><failure message=\"$echappe\"/></testcase>")
  else JUNIT_CAS+=("<testcase classname=\"fumee\" name=\"$id $lib\"/>"); fi
  if [[ "$statut" == ECHEC ]]; then terminer; fi
}
terminer() {
  if [[ -n "$JUNIT" ]]; then
    { printf '<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="fumee-ged" tests="%d" failures="%d" time="%d">\n' \
        "${#JUNIT_CAS[@]}" "$NB_ECHEC" "$(( $(date +%s) - DEBUT_TOTAL ))"
      printf '  %s\n' "${JUNIT_CAS[@]}"; printf '</testsuite>\n'; } > "$JUNIT"
  fi
  bilan "fumée ${GED_URL}"; exit $?
}

info "cible : $GED_URL — fichier : $(basename "$FICHIER") ($(wc -c < "$FICHIER") octets)"

# F01 — santé (la sonde peut mettre quelques secondes à passer UP après un redémarrage)
fin=$(( $(date +%s) + DELAI_SANTE ))
while :; do
  api_appel GET /actuator/health
  [[ "$HTTP_CODE" == 200 ]] && grep -q '"UP"' "$HTTP_CORPS" && break
  (( $(date +%s) >= fin )) && break
  sleep 3
done
if [[ "$HTTP_CODE" == 200 ]] && grep -q '"UP"' "$HTTP_CORPS"; then etape F01 OK "Sonde de santé UP"
elif [[ "$HTTP_CODE" == 000 ]]; then etape F01 ECHEC "Sonde de santé UP" "injoignable après ${DELAI_SANTE} s : $(erreur_curl)"
else etape F01 ECHEC "Sonde de santé UP" "HTTP $HTTP_CODE après ${DELAI_SANTE} s : $(head -c 200 "$HTTP_CORPS")"; fi

# F02 — connexion
api_connexion; rc=$?
case $rc in
  0) etape F02 OK "Connexion" ;;
  2) etape F02 ECHEC "Connexion" "aucun identifiant fourni (GED_RECETTE_IDENTIFIANT/MOT_DE_PASSE, JETON ou CLE_API)" ;;
  *) etape F02 ECHEC "Connexion" "HTTP $HTTP_CODE $(code_metier)" ;;
esac

# F03 — l'API protégée reconnaît l'appelant
api_appel GET /api/v1/auth/me
if [[ "$HTTP_CODE" == 200 ]]; then etape F03 OK "Appel authentifié (/auth/me)"
elif [[ -n "${GED_RECETTE_CLE_API:-}" ]]; then etape F03 OK "Appel authentifié" "clé API : /auth/me non applicable (HTTP $HTTP_CODE)"
else etape F03 ECHEC "Appel authentifié (/auth/me)" "HTTP $HTTP_CODE"; fi

# F04 — dépôt, sous un nom unique qui sert ensuite de critère de recherche
MARQUE="fumee$(date +%Y%m%d%H%M%S)$RANDOM"
EXT="${FICHIER##*.}"
api_depot "$FICHIER" "${MARQUE}.${EXT}"
UUID_RE='^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
if [[ -n "$DEPOT_ID" && "${GED_EXIGER_UUID:-0}" == 1 && ! "$DEPOT_ID" =~ $UUID_RE ]]; then
  etape F04 ECHEC "Dépôt d'un document" "identifiant « $DEPOT_ID » non UUID (DAT §5.3.2, §12.1 ; GED_EXIGER_UUID=1)"
elif [[ -n "$DEPOT_ID" ]]; then etape F04 OK "Dépôt d'un document" "HTTP $HTTP_CODE, id $DEPOT_ID"
elif [[ "$HTTP_CODE" == 000 ]]; then etape F04 ECHEC "Dépôt d'un document" "requête non aboutie : $(erreur_curl)"
else etape F04 ECHEC "Dépôt d'un document" "HTTP $HTTP_CODE $(code_metier) $(head -c 300 "$HTTP_CORPS")"; fi

# F05 — recherche : le document déposé est retrouvé par son nom
trouve=0
for _ in 1 2 3 4 5; do
  api_recherche "$MARQUE"
  # L'identifiant doit apparaître comme valeur d'un champ "id" (un simple « 1 » se
  # trouverait n'importe où dans la réponse).
  if [[ "$HTTP_CODE" == 200 ]] && tr -d ' \r\n' < "$HTTP_CORPS" | grep -q -E "\"id\":\"?${DEPOT_ID}\"?[,}]"; then
    trouve=1; break
  fi
  sleep 2
done
[[ $trouve == 1 ]] && etape F05 OK "Recherche du document déposé" \
                   || etape F05 ECHEC "Recherche du document déposé" "HTTP $HTTP_CODE, id $DEPOT_ID absent des résultats"

# F06 — téléchargement : octets identiques au fichier déposé (chiffrement transparent)
api_telecharger "$DEPOT_ID" "$API_TMP/telecharge"
if [[ "$HTTP_CODE" == 200 && "$(sha256_de "$API_TMP/telecharge")" == "$(sha256_de "$FICHIER")" ]]; then
  etape F06 OK "Téléchargement identique à l'original (SHA-256)"
else
  etape F06 ECHEC "Téléchargement identique à l'original (SHA-256)" "HTTP $HTTP_CODE, $(wc -c < "$API_TMP/telecharge") octets reçus"
fi

# F07 — nettoyage facultatif
if [[ "$NETTOYER" == 1 ]]; then
  api_supprimer "$DEPOT_ID"
  [[ "$HTTP_CODE" =~ ^20[04]$ ]] && etape F07 OK "Suppression douce du document de fumée" \
                                 || etape F07 ECHEC "Suppression douce du document de fumée" "HTTP $HTTP_CODE"
else
  resultat F07 NA "Suppression du document de fumée" "conservé (id $DEPOT_ID, nom $MARQUE) ; --nettoyer pour le supprimer"
fi
terminer
