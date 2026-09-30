#!/usr/bin/env bash
# Recette E10 — T-006 (DAT 2.2, 10.1) et T-066 (DAT 6.1.5, 6.2.1, 6.2.2) : NGINX et paquet Angular.
#
# NGINX est ABSENT du poste de recette : la configuration est vérifiée par relecture
# automatisée (grep) de deploiement/nginx/ged.conf, et confrontée au paquet Angular
# réellement construit HORS LIGNE (node_modules déjà présent, aucune installation) dans
# une copie du front hors dépôt (rien n'est écrit dans frontend/).
#
# Usage : verifier-front-nginx.sh REPERTOIRE_DE_TRAVAIL [NODE_MODULES]

source "$(dirname "${BASH_SOURCE[0]}")/../lib/commun.sh"
T="${1:?répertoire de travail}"; mkdir -p "$T"
NM="${2:-$(readlink -f "$DEPOT_RACINE/frontend/node_modules" 2>/dev/null)}"
CONF="$DEPOT_RACINE/deploiement/nginx/ged.conf"
natif() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

# ---------- Construction hors ligne du paquet Angular ----------
F="$T/front"
if [[ ! -d "$NM/@angular/build" ]]; then
  resultat T-006.build NA "node_modules absent : construction non tentée (aucune installation autorisée)" "$NM"
else
  # La jonction node_modules d'une exécution précédente est retirée SEULE d'abord (rmdir
  # ne suit pas une jonction) : jamais de rm -r à travers elle vers le vrai node_modules.
  if [[ -e "$F/node_modules" ]]; then
    if [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* ]]; then cmd //c rmdir "$(natif "$F/node_modules")"; else rm "$F/node_modules"; fi
  fi
  rm -rf "$F"; mkdir -p "$F"
  (cd "$DEPOT_RACINE/frontend" && cp -r angular.json package.json package-lock.json tsconfig.json tsconfig.app.json src public "$F/")
  if [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* ]]; then
    cmd //c mklink //J "$(natif "$F/node_modules")" "$(natif "$NM")" > /dev/null || fatal "jonction node_modules"
  else
    ln -s "$NM" "$F/node_modules"
  fi
  # CI=true : cache Angular désactivé (aucun .angular/ écrit), pas d'invite d'analytique.
  (cd "$F" && CI=true NG_CLI_ANALYTICS=false ./node_modules/.bin/ng build > "$T/ng-build.log" 2>&1)
  code=$?
  if [[ $code -eq 0 ]]; then
    resultat T-006.build OK "ng build (production) hors ligne" "$(grep -E 'Output location|Application bundle generation complete' "$T/ng-build.log" | tr -s ' ' | tr '\n' ' ' | cut -c1-200)"
  else
    resultat T-006.build ECHEC "ng build en échec (code $code)" "$(grep -iE 'error' "$T/ng-build.log" | head -3 | tr '\n' ' ' | cut -c1-300)"
  fi
fi
B="$F/dist/frontend/browser"

# ---------- T-006 : racine servie = sortie du build ----------
root=$(grep -E '^\s*root /opt/ged/front/courant;' "$CONF")
grep -q 'contenu de frontend/dist/frontend/browser' "$CONF" && [[ -n "$root" ]] \
  && resultat T-006.racine OK "root NGINX = lien courant du paquet dist/frontend/browser" "$root"
grep -q '"outputPath"' "$DEPOT_RACINE/frontend/angular.json" \
  && resultat T-006.outputPath AVERT "outputPath explicite dans angular.json : à comparer" "" \
  || resultat T-006.outputPath OK "outputPath par défaut (dist/<projet>/browser, builder application)" ""
if [[ -f "$B/index.html" ]]; then
  resultat T-006.paquet OK "index.html à la racine de dist/frontend/browser (archive de la CI : tar -C dist/frontend/browser)" ""
  grep -qE '<base href="\./" ?/?>' "$B/index.html" && resultat T-006.base OK "base href relative (./)" "" \
    || resultat T-006.base AVERT "base href inattendue" "$(grep -o '<base[^>]*>' "$B/index.html")"
  cfg=$(cat "$B/assets/config.json" 2>/dev/null | tr -d ' \r\n')
  resultat T-006.config-paquet OK "assets/config.json présent dans le paquet, remplacé par l'alias NGINX" "$cfg"
fi
grep -qE 'provideRouter\(routes, withHashLocation\(\)\)' "$DEPOT_RACINE/frontend/src/app/app.config.ts" \
  && grep -qE 'try_files \$uri \$uri/ =404;' "$CONF" \
  && resultat T-006.repli-spa OK "routage par ancre (withHashLocation) : aucun repli index.html requis, try_files ... =404" ""
grep -qE 'location = /assets/config.json \{' "$CONF" && grep -qE 'alias /etc/ged/front/config.json;' "$CONF" \
  && resultat T-006.config-json OK "config.json d'environnement servi hors paquet (DAT 10.1)" ""
grep -q "assets/config.json" "$DEPOT_RACINE/frontend/src/app/app.config.ts" \
  && resultat T-006.config-front OK "le front lit assets/config.json (même chemin que l'alias)" ""

# ---------- T-066 : durcissement (DAT 6.2.2), TLS (6.2.1), taille (6.1.5) ----------
n=$(grep -c 'server_tokens off;' "$CONF"); [[ $n -ge 2 ]] && resultat T-066.server_tokens OK "server_tokens off dans les deux blocs server" "n=$n" \
  || resultat T-066.server_tokens ECHEC "server_tokens off manquant" "n=$n"
for h in Strict-Transport-Security X-Content-Type-Options X-Frame-Options Content-Security-Policy; do
  grep -qE "^\s*add_header $h .* always;" "$CONF" && resultat "T-066.$h" OK "$h posé (always)" "" \
    || resultat "T-066.$h" ECHEC "$h absent" ""
done
# add_header non hérité si un bloc location en déclare un : aucun ne doit en déclarer.
nloc=$(sed 's/#.*//' "$CONF" | awk '
  /^[ \t]*location[ \t]/ { inloc = 1; d = 0 }
  inloc { if ($0 ~ /add_header/) c++; o = gsub(/\{/, "{"); f = gsub(/\}/, "}"); d += o - f; if (f > 0 && d <= 0) inloc = 0 }
  END { print c + 0 }')
[[ "$nloc" == 0 ]] && resultat T-066.heritage OK "aucun add_header dans un bloc location (en-têtes hérités partout)" "" \
  || resultat T-066.heritage ECHEC "add_header dans un bloc location : en-têtes de sécurité perdus" "n=$nloc"
grep -qE 'return 301 https://\$host\$request_uri;' "$CONF" && resultat T-066.redirection OK "HTTP -> HTTPS 301" ""
grep -qE '^\s*ssl_protocols TLSv1.2 TLSv1.3;' "$CONF" && resultat T-066.tls OK "TLS 1.2 et 1.3 seuls (6.2.1)" ""
grep -qE 'limit_req zone=ged_auth ' "$CONF" && grep -qE 'limit_req zone=ged_api_cle ' "$CONF" \
  && resultat T-066.limit_req OK "limit_req sur /api/v1/auth/ et /api/" "$(grep -E '^limit_req_zone' "$CONF" | awk '{print $3,$4}' | tr '\n' ' ')"
cmb=$(grep -oE 'client_max_body_size [0-9]+m' "$CONF" | awk '{print $2}')
mp=$(grep -hoE 'max-(file|request)-size: *[0-9]+MB|plafond-plateforme-mo: *[0-9]+' "$DEPOT_RACINE/backend/src/main/resources/application.yml" | tr '\n' ' ')
resultat T-066.taille OK "client_max_body_size=$cmb (plateforme 200 Mo + champs multipart), Spring : $mp" ""

# CSP contre le paquet réellement construit.
if [[ -f "$B/index.html" ]]; then
  inl=$(grep -oE '<script[^>]*>' "$B/index.html" | grep -vc 'src=')
  [[ "$inl" == 0 ]] && resultat T-066.csp-script OK "aucun <script> en ligne dans index.html (script-src 'self')" "" \
    || resultat T-066.csp-script ECHEC "script en ligne dans index.html, bloqué par script-src 'self'" "n=$inl"
  grep -qiE ' on[a-z]+="' "$B/index.html" && resultat T-066.csp-handler ECHEC "gestionnaire d'événement en ligne dans index.html" "$(grep -oiE ' on[a-z]+="[^"]*"' "$B/index.html" | head -2)" \
    || resultat T-066.csp-handler OK "aucun gestionnaire en ligne (inlineCritical: false)" ""
  ext=$(grep -ohE '(https?:)?//[a-zA-Z0-9.-]+\.[a-z]{2,}[^"'"'"' )]*' "$B"/*.css "$B"/*.js 2>/dev/null | grep -vE '//www\.w3\.org|//angular\.dev|//g\.co|//github\.com|//material\.angular\.dev|//primefaces' | sort -u | head -5 | tr '\n' ' ')
  [[ -z "$ext" ]] && resultat T-066.csp-externe OK "aucune ressource externe chargée (connect/font/img 'self')" "" \
    || resultat T-066.csp-externe AVERT "URL externes dans le paquet (à qualifier)" "$ext"
  nf=$(find "$B" -name '*.woff2' | wc -l); resultat T-066.csp-polices OK "polices servies localement (font-src 'self')" "woff2=$nf"
fi
bilan "T-006 / T-066 front et NGINX (relecture, NGINX absent)"
