#!/usr/bin/env bash
# Client HTTP minimal de la recette (bash + curl, sans jq) : connexion, dépôt,
# recherche, téléchargement. Partagé par le test de fumée et les recettes E5.
#
# Tout ce qui dépend du contrat d'API est paramétrable par variable d'environnement,
# parce que le contrat évolue pendant la mise en conformité (E2 : connexion LDAP ;
# E9 : chemins /documents/{id}/contenu, /recherches, clés API). Valeurs par défaut :
# l'API telle qu'elle est aujourd'hui.
#
#   GED_URL                    URL de base (ex. https://ged-uat.marchicamed.ma) — obligatoire
#   GED_RECETTE_IDENTIFIANT    identifiant de connexion   } ou GED_RECETTE_JETON (jeton déjà obtenu)
#   GED_RECETTE_MOT_DE_PASSE   mot de passe               } ou GED_RECETTE_CLE_API (en-tête X-API-Key, E9)
#   GED_API_CONNEXION          défaut /api/v1/auth/login
#   GED_CHAMP_IDENTIFIANT      défaut email          GED_CHAMP_MOT_DE_PASSE  défaut motDePasse
#   GED_API_DEPOT              défaut /api/v1/documents (multipart : file, name, typeDocumentId)
#   GED_API_TELECHARGEMENT     défaut /api/v1/documents/{id}/download   (contrat E9 : /api/v1/documents/{id}/contenu)
#   GED_API_RECHERCHE          défaut /api/v1/documents?search={terme}&size=50 (GET)
#                              (contrat E9 : POST /api/v1/recherches, voir GED_API_RECHERCHE_CORPS)
#   GED_API_RECHERCHE_CORPS    si défini, recherche en POST avec ce corps JSON ({terme} substitué)
#   GED_API_SUPPRESSION        défaut /api/v1/documents/{id} (DELETE, suppression douce)
#   GED_TYPE_DOCUMENT_ID       type de document du dépôt (défaut : le premier de /api/v1/type-documents/for-select)
#   GED_CURL_OPTS              options curl supplémentaires (ex. --cacert chaine-mmed.pem)
# Aucun secret n'est écrit sur disque ni dans les traces : le jeton reste en mémoire.

GED_API_CONNEXION="${GED_API_CONNEXION:-/api/v1/auth/login}"
GED_CHAMP_IDENTIFIANT="${GED_CHAMP_IDENTIFIANT:-email}"
GED_CHAMP_MOT_DE_PASSE="${GED_CHAMP_MOT_DE_PASSE:-motDePasse}"
GED_API_DEPOT="${GED_API_DEPOT:-/api/v1/documents}"
# Valeurs contenant des accolades : pas de ${X:-…{id}…}, l'accolade du gabarit fermerait
# l'expansion (la valeur par défaut serait tronquée à « …/{id »).
[[ -n "${GED_API_TELECHARGEMENT:-}" ]] || GED_API_TELECHARGEMENT='/api/v1/documents/{id}/download'
[[ -n "${GED_API_RECHERCHE:-}" ]] || GED_API_RECHERCHE='/api/v1/documents?search={terme}&size=50'
[[ -n "${GED_API_SUPPRESSION:-}" ]] || GED_API_SUPPRESSION='/api/v1/documents/{id}'

API_TMP="$(mktemp -d "${TMPDIR:-/tmp}/qa-api.XXXXXX")"
HTTP_CODE=""; HTTP_CORPS="$API_TMP/corps"; HTTP_ENTETES="$API_TMP/entetes"; _JETON="${GED_RECETTE_JETON:-}"

_auth() {
  if [[ -n "${GED_RECETTE_CLE_API:-}" ]]; then printf 'X-API-Key: %s' "$GED_RECETTE_CLE_API"
  elif [[ -n "$_JETON" ]]; then printf 'Authorization: Bearer %s' "$_JETON"
  else printf 'X-Recette: sans-authentification'; fi
}

# api_appel METHODE CHEMIN [options curl…] → HTTP_CODE, corps dans $HTTP_CORPS
api_appel() {
  local methode="$1" chemin="$2"; shift 2
  : > "$HTTP_CORPS"   # une requête non aboutie ne doit pas laisser le corps de la précédente
  # shellcheck disable=SC2086
  HTTP_CODE="$(curl -sS ${GED_CURL_OPTS:-} -X "$methode" -H "$(_auth)" -H 'Accept: application/json, application/problem+json, */*' \
                 -D "$HTTP_ENTETES" -o "$HTTP_CORPS" -w '%{http_code}' --max-time "${GED_DELAI_HTTP:-300}" \
                 "$@" "${GED_URL%/}$chemin" 2>"$API_TMP/erreur")" || HTTP_CODE="000"
}

# Extraction naïve d'un champ JSON de premier niveau (chaîne ou nombre) : suffit pour
# id, token, code, status. Première occurrence.
json_champ() {  # $1 champ [$2 fichier]
  local f="${2:-$HTTP_CORPS}"
  tr -d '\r\n' < "$f" | grep -o "\"$1\"[[:space:]]*:[[:space:]]*\(\"[^\"]*\"\|[-0-9.a-zA-Z]*\)" | head -1 \
    | sed -E "s/^\"$1\"[[:space:]]*:[[:space:]]*//; s/^\"//; s/\"$//"
}

# Code métier d'une erreur problem+json (RFC 7807) : champ « code » (format retenu,
# §5.3.2) ; repli sur « erreur »/« error » tant que le format maison subsiste.
code_metier() { local c; c="$(json_champ code)"; [[ -z "$c" ]] && c="$(json_champ erreur)"; printf '%s' "$c"; }

api_connexion() {
  [[ -n "$_JETON" || -n "${GED_RECETTE_CLE_API:-}" ]] && return 0
  [[ -n "${GED_RECETTE_IDENTIFIANT:-}" && -n "${GED_RECETTE_MOT_DE_PASSE:-}" ]] || return 2
  local corps id mdp
  # Échappement JSON minimal : un guillemet ou une barre oblique inverse dans le mot
  # de passe ne doit pas casser le corps (et faire croire à un refus d'authentification).
  # (sed plutôt que ${x//…} : le traitement des « \ » dans le remplacement varie selon la version de bash)
  id="$(printf '%s' "$GED_RECETTE_IDENTIFIANT" | sed -e 's/[\\]/&&/g' -e 's/"/\\"/g')"
  mdp="$(printf '%s' "$GED_RECETTE_MOT_DE_PASSE" | sed -e 's/[\\]/&&/g' -e 's/"/\\"/g')"
  corps="$(printf '{"%s":"%s","%s":"%s"}' "$GED_CHAMP_IDENTIFIANT" "$id" "$GED_CHAMP_MOT_DE_PASSE" "$mdp")"
  api_appel POST "$GED_API_CONNEXION" -H 'Content-Type: application/json' --data-binary @- <<< "$corps"
  [[ "$HTTP_CODE" == 200 ]] || return 1
  _JETON="$(json_champ accessToken)"; [[ -z "$_JETON" ]] && _JETON="$(json_champ token)"
  [[ -z "$_JETON" ]] && _JETON="$(json_champ access_token)"
  : > "$HTTP_CORPS"   # le jeton ne reste pas dans un fichier temporaire
  [[ -n "$_JETON" ]]
}

type_document_par_defaut() {
  [[ -n "${GED_TYPE_DOCUMENT_ID:-}" ]] && { printf '%s' "$GED_TYPE_DOCUMENT_ID"; return; }
  api_appel GET /api/v1/type-documents/for-select
  [[ "$HTTP_CODE" == 200 ]] && json_champ id
}

# Chemin lisible par curl : sous Git Bash, curl est un exécutable Windows qui ne comprend
# pas /c/Users/… à l'intérieur d'un argument « file=@… » (aucune conversion automatique).
chemin_natif() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

# Message d'erreur de curl (HTTP 000 : connexion refusée, TLS, fichier illisible…)
erreur_curl() { tr -d '
' < "$API_TMP/erreur" | head -c 300; }

# api_depot FICHIER [NOM] [TYPE_ID] → HTTP_CODE ; DEPOT_ID si succès
api_depot() {
  local fichier="$1" nom="${2:-$(basename "$1")}" type="${3:-${_TYPE_ID:-}}"
  if [[ -z "$type" ]]; then _TYPE_ID="$(type_document_par_defaut)"; type="$_TYPE_ID"; fi
  local extra=()
  [[ -n "${GED_IDEMPOTENCE:-}" ]] && extra+=(-H "Idempotency-Key: $(uuid_aleatoire)")
  api_appel POST "$GED_API_DEPOT" -F "file=@$(chemin_natif "$fichier");filename=${nom}" -F "name=${nom%.*}" -F "typeDocumentId=${type}" "${extra[@]}"
  DEPOT_ID=""
  [[ "$HTTP_CODE" =~ ^20[12]$ ]] && DEPOT_ID="$(json_champ id)"
  return 0
}

# gabarit MODELE NOM VALEUR : remplace {NOM} par VALEUR. Pas de ${x//\{nom\}/v} : selon
# la version de bash, les accolades échappées ferment l'expansion trop tôt.
gabarit() {
  local modele="$1" cle="{$2}" valeur="$3" resultat=""
  while [[ "$modele" == *"$cle"* ]]; do
    resultat+="${modele%%"$cle"*}$valeur"; modele="${modele#*"$cle"}"
  done
  printf '%s' "$resultat$modele"
}

# api_telecharger ID SORTIE → HTTP_CODE, fichier SORTIE
api_telecharger() {
  local chemin; chemin="$(gabarit "$GED_API_TELECHARGEMENT" id "$1")"
  api_appel GET "$chemin"
  cp "$HTTP_CORPS" "$2"
}

# api_recherche TERME → HTTP_CODE, corps de la réponse
api_recherche() {
  local terme; terme="$(printf '%s' "$1" | sed 's/ /%20/g')"
  if [[ -n "${GED_API_RECHERCHE_CORPS:-}" ]]; then
    api_appel POST "${GED_API_RECHERCHE%%\?*}" -H 'Content-Type: application/json' --data-binary "$(gabarit "$GED_API_RECHERCHE_CORPS" terme "$1")"
  else
    api_appel GET "$(gabarit "$GED_API_RECHERCHE" terme "$terme")"
  fi
}

api_supprimer() { api_appel DELETE "$(gabarit "$GED_API_SUPPRESSION" id "$1")"; }

sha256_de() { sha256sum "$1" | cut -d' ' -f1; }

api_nettoyer_tmp() { rm -rf "$API_TMP"; }
