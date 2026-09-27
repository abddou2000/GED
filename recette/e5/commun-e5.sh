#!/usr/bin/env bash
# Socle commun des recettes E5 pilotées par l'API (altération, antivirus, type réel, taille).
#
# Options communes (après les options propres à chaque script) :
#   --url URL        instance visée (ou GED_URL)
#   --racine DOSSIER racine du stockage des fichiers, lue sur le serveur (nécessaire pour
#                    prouver qu'un fichier refusé n'a RIEN écrit, et pour l'altération)
#   --nettoyer       suppression douce des documents déposés par le test
# Identifiants et contrat d'API : voir recette/lib/api.sh.

source "$(dirname "${BASH_SOURCE[0]}")/../lib/commun.sh"
RACINE_STOCKAGE=""; NETTOYER=0; ARGS_RESTANTS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --url) export GED_URL="$2"; shift 2 ;;
    --racine) RACINE_STOCKAGE="${2%/}"; shift 2 ;;
    --nettoyer) NETTOYER=1; shift ;;
    *) ARGS_RESTANTS+=("$1"); shift ;;
  esac
done
set -- "${ARGS_RESTANTS[@]}"
[[ -n "${GED_URL:-}" ]] || fatal "URL de base manquante (--url ou GED_URL)"
source "$RECETTE_RACINE/lib/api.sh"
DONNEES="$RECETTE_RACINE/donnees"
DEPOSES=()
trap 'nettoyer_depots; api_nettoyer_tmp' EXIT

nettoyer_depots() {
  [[ "$NETTOYER" == 1 ]] || { [[ ${#DEPOSES[@]} -gt 0 ]] && info "documents de test conservés : ${DEPOSES[*]} (--nettoyer pour les supprimer)"; return 0; }
  local id; for id in "${DEPOSES[@]}"; do api_supprimer "$id"; done
}

connexion_ou_abandon() {
  api_connexion || fatal "connexion impossible (HTTP $HTTP_CODE) : GED_RECETTE_IDENTIFIANT / MOT_DE_PASSE, JETON ou CLE_API"
}

# Nombre de fichiers publiés dans le stockage (hors temporaires d'écriture).
compter_stockes() {
  [[ -n "$RACINE_STOCKAGE" ]] || { echo -1; return; }
  find "$RACINE_STOCKAGE" -type f ! -name '.*.tmp' 2>/dev/null | wc -l | tr -d ' '
}

# depot_refuse ID LIBELLE FICHIER NOM TYPE HTTP_ATTENDU CODE_METIER_ATTENDU
# Un refus est conforme si : code HTTP attendu, code métier attendu dans le corps
# problem+json, et AUCUN fichier ajouté au stockage (contrôle fait avant l'écriture, §12.11).
depot_refuse() {
  local id="$1" lib="$2" fichier="$3" nom="$4" type="$5" http="$6" metier="$7" avant apres code detail=""
  avant="$(compter_stockes)"
  api_depot "$fichier" "$nom" "$type"
  apres="$(compter_stockes)"; code="$(code_metier)"
  [[ -n "$DEPOT_ID" ]] && DEPOSES+=("$DEPOT_ID")
  if [[ "$HTTP_CODE" != "$http" ]]; then
    resultat "$id" ECHEC "$lib" "HTTP $HTTP_CODE (attendu $http) ${code:+code $code} ${DEPOT_ID:+— document accepté, id $DEPOT_ID}"; return
  fi
  [[ -n "$metier" && "$code" != "$metier" ]] && detail+="code métier « ${code:-absent} » au lieu de $metier ; "
  [[ "$avant" != -1 && "$avant" != "$apres" ]] && detail+="$((apres - avant)) fichier(s) écrit(s) dans le stockage malgré le refus ; "
  if [[ -n "$detail" ]]; then resultat "$id" ECHEC "$lib" "${detail%; }"
  else resultat "$id" OK "$lib" "HTTP $HTTP_CODE ${code}$([[ "$avant" == -1 ]] && echo ' (stockage non vérifié : --racine absent)')"; fi
}

# depot_accepte ID LIBELLE FICHIER NOM TYPE → OK si 201/202
depot_accepte() {
  local id="$1" lib="$2"
  api_depot "$3" "$4" "$5"
  if [[ -n "$DEPOT_ID" ]]; then DEPOSES+=("$DEPOT_ID"); resultat "$id" OK "$lib" "HTTP $HTTP_CODE, id $DEPOT_ID"; return 0
  else resultat "$id" ECHEC "$lib" "HTTP $HTTP_CODE $(code_metier) $([[ "$HTTP_CODE" == 000 ]] && erreur_curl)"; return 1; fi
}

# Fichier EICAR reconstitué à la demande (jamais versionné : voir donnees/README.md).
ecrire_eicar() { printf '%s%s' 'X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-' 'ANTIVIRUS-TEST-FILE!$H+H*' > "$1"; }
