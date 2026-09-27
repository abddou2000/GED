#!/usr/bin/env bash
# Recette E2 — identité et sessions, de bout en bout par l'API (DAT V3 §3.2 à §3.4 ;
# décisions D1, D2, D3, D4 de la revue technique).
#
# Critère de sortie E2 : connexion avec un compte AD de test, aucun mot de passe en base,
# révocation effective immédiatement.
#
# Comptes de l'annuaire de test (simulateur UnboundID en DEV ; comptes de test AD en UAT),
# désignés par variables — aucun identifiant ni mot de passe écrit ici :
#   GED_E2_ADMIN (Administrateur)       GED_E2_NOUVEAU (jamais connecté : provisionnement)
#   GED_E2_STANDARD (compte actif)      GED_E2_SANS_ROLE (actif, aucun rôle)
#   GED_E2_DESACTIVE (désactivé dans l'annuaire)
#   GED_RECETTE_MOT_DE_PASSE            mot de passe commun des comptes de test
#   GED_E2_MOT_DE_PASSE_<COMPTE>        (facultatif) mot de passe propre à un compte
#
# Usage : verifier-identite.sh --url URL [--base NOM_BASE] [--front DOSSIER_FRONT]
#   --base   base PostgreSQL de l'instance (contrôles du schéma et de la table session ;
#            connexion libpq par PGHOST/PGUSER/…), facultatif
#   --front  sources Angular (frontend/src) pour l'inspection du stockage du jeton
# Dure 3 à 4 minutes : la limitation de débit (5 connexions par minute et par adresse IP,
# §3.4.1) impose d'espacer les connexions ; le script attend quand l'application répond 429.
# Sortie : RESULTAT|…, BILAN ; code 0 si aucun ECHEC.

source "$(dirname "${BASH_SOURCE[0]}")/../lib/commun.sh"
ICI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE=""; FRONT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --url) GED_URL="$2"; shift 2 ;;
    --base) BASE="$2"; shift 2 ;;
    --front) FRONT="$2"; shift 2 ;;
    -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
    *) fatal "option inconnue : $1" ;;
  esac
done
[[ -n "${GED_URL:-}" ]] || fatal "--url obligatoire"
GED_URL="${GED_URL%/}"
for v in GED_E2_ADMIN GED_E2_STANDARD GED_E2_NOUVEAU GED_E2_SANS_ROLE GED_E2_DESACTIVE GED_RECETTE_MOT_DE_PASSE; do
  [[ -n "${!v:-}" ]] || fatal "variable $v obligatoire (voir l'en-tête du script)"
done
T="$(mktemp -d "${TMPDIR:-/tmp}/qa-e2.XXXXXX")"; trap 'rm -rf "$T"' EXIT
NOM_COOKIE="${GED_NOM_COOKIE:-ged_renouvellement}"
ENTETE_CSRF="${GED_ENTETE_CSRF:-X-GED-Renouvellement}"

mdp_de() { local v="GED_E2_MOT_DE_PASSE_$(printf '%s' "$1" | tr '[:lower:]' '[:upper:]')"; printf '%s' "${!v:-$GED_RECETTE_MOT_DE_PASSE}"; }
json_echappe() { printf '%s' "$1" | sed -e 's/[\\]/&&/g' -e 's/"/\\"/g'; }
champ() { tr -d '\r\n' < "${2:-$T/corps}" | grep -o "\"$1\"[[:space:]]*:[[:space:]]*\(\"[^\"]*\"\|[-0-9.a-z]*\)" | head -1 | sed -E "s/^\"$1\"[[:space:]]*:[[:space:]]*//; s/^\"//; s/\"$//"; }
b64url() { local s; s="$(printf '%s' "$1" | tr '_-' '/+')"; while (( ${#s} % 4 )); do s+="="; done; printf '%s' "$s" | base64 -d 2>/dev/null; }
b64url_enc() { base64 -w0 | tr '/+' '_-' | tr -d '='; }

# appel METHODE CHEMIN [options curl] → CODE ; corps $T/corps ; en-têtes $T/entetes
appel() {
  local m="$1" c="$2"; shift 2
  : > "$T/corps"
  CODE="$(curl -sS -X "$m" -D "$T/entetes" -o "$T/corps" -w '%{http_code}' --max-time 60 "$@" "$GED_URL$c" 2>"$T/err")" || CODE=000
  tr -d '\r' < "$T/entetes" > "$T/entetes.n"
}
cookie_recu() { grep -i "^set-cookie: $NOM_COOKIE=" "$T/entetes.n" | head -1 | sed -E "s/^[^=]*=([^;]*).*/\1/"; }
attributs_cookie() { grep -i "^set-cookie: $NOM_COOKIE=" "$T/entetes.n" | head -1 | cut -d';' -f2-; }

# connecter COMPTE [MOT_DE_PASSE] → CODE, JETON, COOKIE ; attend si 429 (limitation de débit).
connecter() {
  local compte="$1" mdp="${2-$(mdp_de "$1")}" essai
  for essai in 1 2 3; do
    appel POST /api/v1/auth/login -H 'Content-Type: application/json' \
      --data-binary "{\"identifiant\":\"$(json_echappe "$compte")\",\"motDePasse\":\"$(json_echappe "$mdp")\"}"
    [[ "$CODE" != 429 ]] && break
    local attente; attente="$(grep -i '^retry-after:' "$T/entetes.n" | tr -dc '0-9')"
    info "limitation de débit atteinte : attente de $(( ${attente:-60} + 1 )) s avant de reconnecter $compte"
    sleep $(( ${attente:-60} + 1 ))
  done
  JETON="$(champ token)"; COOKIE="$(cookie_recu)"
  : > "$T/corps.jeton"; [[ -n "$JETON" ]] && cp "$T/corps" "$T/corps.jeton" && sed -i 's/"token":"[^"]*"/"token":"<masqué>"/' "$T/corps"
}
auth() { printf 'Authorization: Bearer %s' "$1"; }

# ----------------------------------------------------------------- schéma
if [[ -n "$BASE" ]]; then
  trouver_psql
  CONSTATS="$(PGDATABASE="$BASE" pg -At -F'|' -v schema="${GED_SCHEMA:-ged}" -f "$ICI/controles-identite.sql")" || fatal "controles-identite.sql"
  CONSTATS="${CONSTATS//$'\r'/}"
  while IFS='|' read -r id lib; do
    ecarts="$(grep "^$id|" <<< "$CONSTATS" | cut -d'|' -f3,4 | tr '\n' ';')"
    [[ -z "$ecarts" ]] && resultat "$id" OK "$lib" || resultat "$id" ECHEC "$lib" "$ecarts"
  done <<'FIN'
E2-C01|Aucune colonne de mot de passe dans le schéma [3.2]
E2-C02|Aucune table de comptes locaux [3.2]
E2-C03|Aucune valeur au format d'empreinte de mot de passe dans toute la base [3.2]
E2-C04|Identité clé objectGUID unique [3.3]
E2-C05|Table session sans jeton en clair, avec empreinte [3.4.1]
E2-C06|Table cache_annuaire présente [3.4.2]
E2-C07|Aucun attribut d'activation ni d'appartenance AD mémorisé [D1, D3, P2]
FIN
else
  resultat E2-C00 NA "Contrôles du schéma d'identité" "--base non fourni"
fi

# ----------------------------------------------------------------- connexion (D2)
connecter "$GED_E2_ADMIN"
ADMIN_JETON="$JETON"
if [[ "$CODE" == 200 && -n "$JETON" ]]; then
  resultat E2-01 OK "Connexion search-then-bind par identifiant d'annuaire (sAMAccountName) [3.3, D2]" "$GED_E2_ADMIN"
else
  resultat E2-01 ECHEC "Connexion par identifiant d'annuaire [3.3, D2]" "HTTP $CODE"; bilan "E2 identité"; exit 1
fi
attr="$(attributs_cookie)"; ok=1; manque=()
for a in HttpOnly Secure "SameSite=Strict" "Path=/api/v1/auth"; do grep -qi "$a" <<< "$attr" || { ok=0; manque+=("$a"); }; done
age="$(grep -o 'Max-Age=[0-9]*' <<< "$attr" | cut -d= -f2)"
(( ${age:-0} > 0 && ${age:-0} <= 28800 )) || { ok=0; manque+=("Max-Age=${age:-absent} (≤ 8 h attendu)"); }
grep -qi '^cache-control:.*no-store' "$T/entetes.n" || { ok=0; manque+=("Cache-Control: no-store"); }
[[ -n "$COOKIE" ]] && grep -qF "$COOKIE" "$T/corps.jeton" && { ok=0; manque+=("jeton de renouvellement présent dans le corps"); }
[[ $ok == 1 ]] && resultat E2-02 OK "Renouvellement en cookie HttpOnly, Secure, SameSite=Strict, chemin /api/v1/auth, ≤ 8 h, jamais dans le corps [3.4.1]" "Max-Age=$age" \
               || resultat E2-02 ECHEC "Cookie de renouvellement conforme [3.4.1]" "${manque[*]}"

ENTETE_JWT="$(b64url "$(cut -d. -f1 <<< "$JETON")")"; CHARGE="$(b64url "$(cut -d. -f2 <<< "$JETON")")"
alg="$(grep -o '"alg":"[^"]*"' <<< "$ENTETE_JWT" | cut -d'"' -f4)"
iat="$(grep -o '"iat":[0-9]*' <<< "$CHARGE" | cut -d: -f2)"; exp="$(grep -o '"exp":[0-9]*' <<< "$CHARGE" | cut -d: -f2)"
cles="$(grep -o '"[a-zA-Z_]*":' <<< "$CHARGE" | tr -d '":' | sort | tr '\n' ' ')"
[[ "$alg" == RS256 ]] && resultat E2-03 OK "Jeton d'accès signé RS256 [3.4.1]" \
                      || resultat E2-03 ECHEC "Jeton d'accès signé RS256 [3.4.1]" "alg=$alg"
[[ -n "$iat" && -n "$exp" && $(( exp - iat )) -le 900 ]] && resultat E2-04 OK "Durée de vie du jeton d'accès ≤ 15 min [3.3, 3.4.1]" "$(( exp - iat )) s" \
                                                        || resultat E2-04 ECHEC "Durée de vie ≤ 15 min" "iat=$iat exp=$exp"
if grep -qiE '"(roles?|authorities|permissions?|scope|scp|groups?|habilitations?)"' <<< "$CHARGE"; then
  resultat E2-05 ECHEC "Jeton sans rôle ni permission embarqués [3.3]" "revendications : $cles"
else
  resultat E2-05 OK "Jeton sans rôle ni permission embarqués [3.3]" "revendications : $cles"
fi

appel GET /api/v1/auth/me -H "$(auth "$ADMIN_JETON")"
[[ "$CODE" == 200 ]] && resultat E2-06 OK "Jeton accepté dans l'en-tête Authorization" || resultat E2-06 ECHEC "Jeton accepté" "HTTP $CODE"

# Jetons forgés et jeton hors en-tête
charge_autre="$(printf '%s' "$CHARGE" | sed -E 's/"sub":"[^"]*"/"sub":"00000000-0000-7000-8000-000000000000"/' | b64url_enc)"
sig="$(cut -d. -f3 <<< "$JETON")"
none="$(printf '{"alg":"none","typ":"JWT"}' | b64url_enc).$(cut -d. -f2 <<< "$JETON")."
hs="$(printf '{"alg":"HS256","typ":"JWT"}' | b64url_enc).$(cut -d. -f2 <<< "$JETON")"
hs="$hs.$(printf '%s' "$hs" | openssl dgst -sha256 -hmac "cle-forgee-par-la-recette" -binary 2>/dev/null | b64url_enc)"
declare -A FORGES=([alg-none]="$none" [hs256]="$hs" [charge-modifiee]="$(cut -d. -f1 <<< "$JETON").$charge_autre.$sig" [signature-tronquee]="${JETON%?????}")
refus=(); for k in "${!FORGES[@]}"; do
  appel GET /api/v1/auth/me -H "$(auth "${FORGES[$k]}")"; [[ "$CODE" == 401 ]] || refus+=("$k→$CODE")
done
appel GET "/api/v1/auth/me?access_token=$ADMIN_JETON"; [[ "$CODE" == 401 ]] || refus+=("paramètre-url→$CODE")
appel GET /api/v1/auth/me -H "Cookie: Authorization=Bearer%20$ADMIN_JETON; access_token=$ADMIN_JETON"; [[ "$CODE" == 401 ]] || refus+=("cookie→$CODE")
[[ ${#refus[@]} -eq 0 ]] && resultat E2-07 OK "Jetons forgés (alg none, HS256, charge modifiée, signature tronquée) et jeton hors en-tête refusés en 401 [3.4.1, P-03]" \
                         || resultat E2-07 ECHEC "Jetons forgés ou hors en-tête refusés" "${refus[*]}"

# D2 : l'adresse e-mail et l'UPN sont refusés AVANT l'annuaire (400) — ne consomment pas le quota
courriel="$(champ email "$T/corps.jeton")"
appel POST /api/v1/auth/login -H 'Content-Type: application/json' \
  --data-binary "{\"identifiant\":\"$(json_echappe "${courriel:-$GED_E2_ADMIN@marchica.ma}")\",\"motDePasse\":\"$(json_echappe "$(mdp_de "$GED_E2_ADMIN")")\"}"
c1="$CODE"
appel POST /api/v1/auth/login -H 'Content-Type: application/json' \
  --data-binary "{\"identifiant\":\"$(json_echappe "$GED_E2_ADMIN@marchicamed.ma")\",\"motDePasse\":\"$(json_echappe "$(mdp_de "$GED_E2_ADMIN")")\"}"
c2="$CODE"
[[ "$c1" == 400 && "$c2" == 400 ]] && resultat E2-08 OK "Connexion par adresse e-mail ou UPN refusée, avec le bon mot de passe [D2]" "e-mail $c1, UPN $c2" \
                                   || resultat E2-08 ECHEC "Connexion par e-mail ou UPN refusée [D2]" "e-mail $c1, UPN $c2"

# Mauvais mot de passe : même réponse pour un compte existant et un compte inconnu
connecter "$GED_E2_ADMIN" "mauvais-mot-de-passe-recette"; ca="$CODE"; msg_a="$(champ message; champ detail)"
connecter "qainconnu$RANDOM" "mauvais-mot-de-passe-recette"; cb="$CODE"; msg_b="$(champ message; champ detail)"
[[ "$ca" == 401 && "$cb" == 401 && "$msg_a" == "$msg_b" ]] \
  && resultat E2-09 OK "Mauvais mot de passe et compte inconnu : 401 indiscernables" \
  || resultat E2-09 ECHEC "Mauvais mot de passe et compte inconnu indiscernables" "existant $ca « $msg_a » / inconnu $cb « $msg_b »"

# D1 : compte désactivé dans l'annuaire, bon mot de passe → refus par l'annuaire lui-même
connecter "$GED_E2_DESACTIVE"
[[ "$CODE" == 401 ]] && resultat E2-10 OK "Compte désactivé dans l'annuaire : connexion refusée (bind refusé) [D1, 3.4.2]" \
                     || resultat E2-10 ECHEC "Compte désactivé : connexion refusée [D1]" "HTTP $CODE"

# ----------------------------------------------------------------- provisionnement (3.3)
if [[ -n "$BASE" ]]; then
  deja="$(PGDATABASE="$BASE" pg -At -c "SELECT count(*) FROM ${GED_SCHEMA:-ged}.utilisateur WHERE lower(identifiant) = lower('$GED_E2_NOUVEAU')" | tr -d '\r')"
fi
connecter "$GED_E2_NOUVEAU"; NOUVEAU_JETON="$JETON"
appel GET /api/v1/auth/me -H "$(auth "$NOUVEAU_JETON")"
NOUVEAU_ID="$(champ id)"; roles="$(tr -d '\r\n ' < "$T/corps" | grep -o '"roles":\[[^]]*\]')"; perms="$(tr -d '\r\n ' < "$T/corps" | grep -o '"permissions":\[[^]]*\]')"
if [[ "${deja:-0}" != 0 ]]; then
  resultat E2-11 NA "Provisionnement à la première connexion" "$GED_E2_NOUVEAU déjà connu de la base : choisir un compte jamais connecté"
elif [[ "$CODE" == 200 && "$roles" == '"roles":[]' && "$perms" == '"permissions":[]' ]]; then
  detail=""
  if [[ -n "$BASE" ]]; then
    ligne="$(PGDATABASE="$BASE" pg -At -F' ' -c "SELECT object_guid, (SELECT count(*) FROM ${GED_SCHEMA:-ged}.habilitation h WHERE h.utilisateur_id = u.id) FROM ${GED_SCHEMA:-ged}.utilisateur u WHERE lower(identifiant) = lower('$GED_E2_NOUVEAU')" | tr -d '\r')"
    detail="objectGUID $(cut -d' ' -f1 <<< "$ligne"), habilitations : $(cut -d' ' -f2 <<< "$ligne")"
    [[ "$(cut -d' ' -f2 <<< "$ligne")" == 0 ]] || { resultat E2-11 ECHEC "Provisionnement sans rôle [3.3]" "$detail"; detail=KO; }
  fi
  [[ "$detail" != KO ]] && resultat E2-11 OK "Première connexion : identité créée sans rôle ni permission, clé objectGUID [3.3]" "$detail"
else
  resultat E2-11 ECHEC "Première connexion : identité sans rôle [3.3]" "HTTP $CODE $roles $perms"
fi
appel GET "/api/v1/documents?size=5" -H "$(auth "$NOUVEAU_JETON")"; cdoc="$CODE"; tot="$(champ total)"
[[ "$cdoc" == 403 || ( "$cdoc" == 200 && "$tot" == 0 ) ]] \
  && resultat E2-12 OK "Identité sans rôle : aucun document (accueil vide) [P-04]" "HTTP $cdoc ${tot:+total $tot}" \
  || resultat E2-12 ECHEC "Identité sans rôle : aucun document" "HTTP $cdoc total ${tot:-?}"

# ----------------------------------------------------------------- renouvellement (3.4.1)
connecter "$GED_E2_STANDARD"; S_JETON="$JETON"; C0="$COOKIE"
appel POST /api/v1/auth/refresh -H "Cookie: $NOM_COOKIE=$C0"
[[ "$CODE" == 403 ]] && resultat E2-13 OK "Renouvellement sans en-tête $ENTETE_CSRF refusé en 403 (CSRF) [P-03]" \
                     || resultat E2-13 ECHEC "Renouvellement sans en-tête CSRF refusé" "HTTP $CODE"
appel POST /api/v1/auth/refresh -H "Cookie: $NOM_COOKIE=$C0" -H "$ENTETE_CSRF: 1"
C1="$(cookie_recu)"; S_JETON1="$(champ token)"
[[ "$CODE" == 200 && -n "$C1" && "$C1" != "$C0" && -n "$S_JETON1" ]] \
  && resultat E2-14 OK "Renouvellement : nouveau jeton d'accès et rotation du jeton de renouvellement [3.4.1]" \
  || resultat E2-14 ECHEC "Renouvellement avec rotation" "HTTP $CODE, cookie changé : $([[ "$C1" != "$C0" ]] && echo oui || echo non)"
if [[ -n "$BASE" && -n "$C1" ]]; then
  empr_hex="$(printf '%s' "$C1" | sha256sum | cut -d' ' -f1)"; empr_b64="$(printf '%s' "$C1" | openssl dgst -sha256 -binary 2>/dev/null | b64url_enc)"
  r="$(PGDATABASE="$BASE" pg -At -c "SELECT (SELECT count(*) FROM ${GED_SCHEMA:-ged}.session s WHERE s::text LIKE '%' || '$C1' || '%'), (SELECT count(*) FROM ${GED_SCHEMA:-ged}.session WHERE empreinte IN ('$empr_hex', '$empr_b64'))" | tr -d '\r')"
  [[ "$r" == "0|1" ]] && resultat E2-15 OK "Table session : empreinte SHA-256 du jeton, jamais le jeton en clair [3.4.1]" \
                      || resultat E2-15 ECHEC "Table session : empreinte seulement" "clair trouvé|empreinte trouvée = $r"
fi
# Réutilisation du jeton déjà consommé → toute la famille est révoquée
appel POST /api/v1/auth/refresh -H "Cookie: $NOM_COOKIE=$C0" -H "$ENTETE_CSRF: 1"; reuse="$CODE"
appel POST /api/v1/auth/refresh -H "Cookie: $NOM_COOKIE=$C1" -H "$ENTETE_CSRF: 1"; frere="$CODE"
appel GET /api/v1/auth/me -H "$(auth "$S_JETON1")"; acces="$CODE"
[[ "$reuse" == 401 && "$frere" == 401 && "$acces" == 401 ]] \
  && resultat E2-16 OK "Réutilisation d'un jeton consommé : famille révoquée, jeton d'accès en cours refusé aussitôt [3.4.1]" \
  || resultat E2-16 ECHEC "Réutilisation : famille révoquée" "rejeu $reuse, jeton suivant $frere, accès en cours $acces (401 attendus)"

# Déconnexion : effet immédiat
connecter "$GED_E2_STANDARD"; D_JETON="$JETON"
appel POST /api/v1/auth/logout -H "$(auth "$D_JETON")"; out="$CODE"
appel GET /api/v1/auth/me -H "$(auth "$D_JETON")"
[[ "$out" =~ ^20 && "$CODE" == 401 ]] && resultat E2-17 OK "Déconnexion : jeton d'accès refusé immédiatement [3.4.1]" \
                                      || resultat E2-17 ECHEC "Déconnexion immédiate" "logout $out, puis /me $CODE"

# Révocation par l'Administrateur : effet immédiat (critère de sortie)
connecter "$GED_E2_SANS_ROLE"; R_JETON="$JETON"; R_COOKIE="$COOKIE"
appel GET /api/v1/auth/me -H "$(auth "$R_JETON")"; R_ID="$(champ id)"; avant="$CODE"
appel DELETE "/api/v1/admin/utilisateurs/$R_ID/sessions" -H "$(auth "$ADMIN_JETON")"; rev="$CODE"
appel GET /api/v1/auth/me -H "$(auth "$R_JETON")"; apres="$CODE"
appel POST /api/v1/auth/refresh -H "Cookie: $NOM_COOKIE=$R_COOKIE" -H "$ENTETE_CSRF: 1"; ren="$CODE"
[[ "$avant" == 200 && "$rev" =~ ^20 && "$apres" == 401 && "$ren" == 401 ]] \
  && resultat E2-18 OK "Révocation par l'Administrateur : jeton d'accès et renouvellement refusés immédiatement [3.4.1, critère E2, R1]" \
  || resultat E2-18 ECHEC "Révocation immédiate par l'Administrateur" "avant $avant, révocation $rev, accès $apres, renouvellement $ren"
appel DELETE "/api/v1/admin/utilisateurs/$R_ID/sessions" -H "$(auth "$NOUVEAU_JETON")"
[[ "$CODE" == 403 ]] && resultat E2-19 OK "Révocation réservée à l'Administrateur (403 pour une identité sans rôle)" \
                     || resultat E2-19 ECHEC "Révocation réservée à l'Administrateur" "HTTP $CODE"

# ----------------------------------------------------------------- D1, D3 : revue du code
SRC="$DEPOT_RACINE/backend/src/main"
if [[ -d "$SRC" ]]; then
  # Le simulateur d'annuaire (SimulateurAnnuaire, AnnuaireEmbarque : profil dev/test) joue le
  # rôle de l'AD, qui refuse lui-même la liaison d'un compte désactivé : il est exclu. Seul
  # compte le code client de la GED.
  lectures="$(grep -rn "userAccountControl" "$SRC/java" | grep -vE '/(SimulateurAnnuaire|AnnuaireEmbarque)\.java:'               | grep -viE 'interdit|exclu|refus|jamais|ATTRIBUTS_INTERDITS|remove|\*|//' | head -3)"
  taches="$(grep -rln "@Scheduled" "$SRC/java/com/ipt/ged/identite" 2>/dev/null | tr '\n' ' ')"
  [[ -z "$lectures" && -z "$taches" ]] && resultat E2-20 OK "Aucune lecture de userAccountControl ni tâche de relecture périodique de l'annuaire [D1]" \
                                       || resultat E2-20 ECHEC "Aucune relecture de l'état AD [D1]" "${lectures}${taches:+ tâches planifiées : $taches}"
  attrs="$(grep -A0 -rh "attributs:" "$SRC/resources/application.yml" | sed 's/.*attributs:[[:space:]]*//')"
  if grep -qiE 'memberOf|userAccountControl|userPrincipalName|distinguishedName|ou\b' <<< "$attrs"; then
    resultat E2-21 ECHEC "Attributs lus : strict minimum [D3, P2]" "$attrs"
  else
    resultat E2-21 OK "Attributs lus : strict minimum, sans appartenance ni état [D3, P2]" "$attrs"
  fi
fi

# ----------------------------------------------------------------- stockage du jeton côté Angular
if [[ -n "$FRONT" && -d "$FRONT" ]]; then
  suspects="$(grep -rnE "(local|session)Storage\.setItem\([^)]*(token|jeton|auth|access|refresh|bearer)" --include=*.ts "$FRONT" | grep -v '\.spec\.ts' | head -3)"
  cookie_js="$(grep -rn "document\.cookie" --include=*.ts "$FRONT" | grep -v '\.spec\.ts' | head -2)"
  jeton_stocke="$(grep -rnE "(local|session)Storage\.(setItem|getItem)\(" --include=*.ts "$FRONT" | grep -v '\.spec\.ts' | grep -iE 'token|jeton|auth\.service|session\.service' | head -3)"
  [[ -z "$suspects$cookie_js$jeton_stocke" ]] \
    && resultat E2-22 OK "Front Angular : jeton jamais écrit dans localStorage, sessionStorage ni document.cookie (revue du code) [3.4.1]" \
    || resultat E2-22 ECHEC "Front Angular : jeton en mémoire seulement" "$suspects $cookie_js $jeton_stocke"
else
  resultat E2-22 NA "Stockage du jeton côté Angular" "--front non fourni"
fi

# ----------------------------------------------------------------- limitation de débit (en dernier : elle bloque les connexions une minute)
info "limitation de débit : attente de 61 s pour partir d'une fenêtre vide"
sleep 61
codes=(); for i in 1 2 3 4 5 6; do
  appel POST /api/v1/auth/login -H 'Content-Type: application/json' --data-binary '{"identifiant":"qafrein","motDePasse":"essai-recette"}'
  codes+=("$CODE")
done
ra="$(grep -i '^retry-after:' "$T/entetes.n" | tr -dc '0-9')"
[[ "${codes[*]:0:5}" == "401 401 401 401 401" && "${codes[5]}" == 429 && -n "$ra" ]] \
  && resultat E2-23 OK "6e tentative en une minute : 429 avec Retry-After [3.4.1]" "codes ${codes[*]}, Retry-After $ra s" \
  || resultat E2-23 ECHEC "5 tentatives par minute puis 429 + Retry-After" "codes ${codes[*]}, Retry-After ${ra:-absent}"
appel POST /api/v1/auth/login -H 'Content-Type: application/json' --data-binary '{"identifiant":"qaautre","motDePasse":"essai-recette"}'
[[ "$CODE" == 429 ]] && resultat E2-24 OK "Limite par adresse IP : autre identifiant, même IP → 429 [3.4.1]" \
                     || resultat E2-24 ECHEC "Limite par adresse IP" "HTTP $CODE"

# Derrière NGINX, l'adresse du client arrive dans X-Forwarded-For (deploiement/nginx/ged.conf) ;
# la GED doit limiter par client, pas par proxy. On se présente en proxy de confiance
# (localhost, GED_PROXYS_DE_CONFIANCE par défaut) avec deux clients distincts.
if [[ "${GED_E2_TESTER_PROXY:-1}" == 1 ]]; then
  info "limitation derrière le proxy : attente de 61 s"
  sleep 61
  for i in 1 2 3 4 5; do
    appel POST /api/v1/auth/login -H 'Content-Type: application/json' -H 'X-Forwarded-For: 10.20.30.41' \
      --data-binary "{\"identifiant\":\"qaclienta$i\",\"motDePasse\":\"essai-recette\"}"
  done
  appel POST /api/v1/auth/login -H 'Content-Type: application/json' -H 'X-Forwarded-For: 10.20.30.42' \
    --data-binary '{"identifiant":"qaclientb","motDePasse":"essai-recette"}'
  [[ "$CODE" == 401 ]] && resultat E2-25 OK "Derrière NGINX : un client n'épuise pas le quota d'un autre (IP lue dans X-Forwarded-For du proxy de confiance) [3.4.1, 6.2.2]" \
                       || resultat E2-25 ECHEC "Derrière NGINX : limitation par client, pas par proxy [3.4.1, 6.2.2]" "client B (autre IP, autre identifiant) reçoit HTTP $CODE après 5 essais du client A"
fi

bilan "E2 identité ${GED_URL}"
