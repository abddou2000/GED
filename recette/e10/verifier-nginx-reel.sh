#!/usr/bin/env bash
# Recette E10 — T-006 (DAT 2.2, 10.1) et T-066 (DAT 6.1.5, 6.2.1, 6.2.2) contre un NGINX RÉEL.
#
# Complète verifier-front-nginx.sh (relecture) : deploiement/nginx/ged.conf LIVRÉ est chargé par un
# vrai NGINX, avec pour seules adaptations les valeurs « À ADAPTER » du fichier (nom DNS gardé,
# certificat de test auto-signé au lieu de celui de MMED, adresse du back-end, racine du paquet) et
# les ports d'écoute (ports non privilégiés du poste). Le diff est imprimé.
#
#   N01 nginx -t sur la configuration adaptée ;
#   N02 HTTP → 301 vers HTTPS, en-tête Server sans version ;
#   N03 TLS 1.0 et 1.1 refusés, 1.2 et 1.3 acceptés (client OpenSSL autorisé à les tenter) ;
#   N04 en-têtes de sécurité sur la page, sur une réponse de l'API et sur une erreur, sans doublon ;
#   N05 paquet Angular servi ; index.html jamais en cache ; fichiers compilés en cache long ;
#       config.json d'ENVIRONNEMENT servi à la place de celui du paquet ; chemin inconnu 404 ;
#       fichiers cachés et Actuator jamais servis ;
#   N06 l'API passe par NGINX ; l'adresse enregistrée par le back-end est celle du CLIENT
#       (X-Forwarded-For posé par NGINX), un X-Forwarded-For forgé par le client est écrasé ;
#   N07 limitation de débit de /api/v1/auth/ : 429 au-delà de 1 + burst (5) ;
#   N08 client_max_body_size : corps de plus de 210 Mo refusé en 413 par NGINX, sans atteindre
#       le back-end ; corps de 205 Mo transmis au back-end, qui le refuse lui-même (plafond 200 Mo) ;
#   N09 proxy_request_buffering off : aucun corps de dépôt écrit sur le disque de NGINX pendant
#       un dépôt de 150 Mo (répertoire client_body_temp surveillé) ;
#   N10 traceparent : l'identifiant créé par NGINX est le traceId du journal du back-end.
#
# Usage : verifier-nginx-reel.sh REPERTOIRE_DE_TRAVAIL PAQUET_ANGULAR
# Variables : GED_URL_BACKEND (défaut http://127.0.0.1:18084), GED_NGINX_HTTP (18184),
#   GED_NGINX_HTTPS (18444), GED_RECETTE_IDENTIFIANT, GED_RECETTE_MOT_DE_PASSE,
#   GED_JOURNAL_BACKEND (ged.log de l'instance), GED_SQL (commande qui exécute une requête SQL
#   sur la base de l'instance et imprime le résultat brut), GED_TYPE_DOCUMENT_TEXTE_ID.

source "$(dirname "${BASH_SOURCE[0]}")/../lib/commun.sh"
T="${1:?répertoire de travail}"; PAQUET="${2:?paquet Angular (dist/frontend/browser)}"
command -v nginx >/dev/null || { resultat T-066.nginx NA "NGINX absent du poste" ""; bilan "NGINX réel"; exit 2; }
BACK="${GED_URL_BACKEND:-http://127.0.0.1:18084}"; HP="${GED_NGINX_HTTP:-18184}"; HS="${GED_NGINX_HTTPS:-18444}"
ID="${GED_RECETTE_IDENTIFIANT:-sbennani}"; MDP="${GED_RECETTE_MOT_DE_PASSE:?}"
CONF="$DEPOT_RACINE/deploiement/nginx/ged.conf"
mkdir -p "$T"/{tls,logs,etc,temp}; rm -rf "$T"/temp/*
N="nginx -p $T -c $T/nginx.conf"
arreter() { [[ -f "$T/nginx.pid" ]] && kill "$(cat "$T/nginx.pid")" 2>/dev/null; sleep 0.5; }
arreter

# --- Préparation : certificat de test, config.json d'environnement, configuration adaptée ------
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=ged.marchica.ma" \
  -keyout "$T/tls/ged.key" -out "$T/tls/ged.fullchain.pem" 2>/dev/null || fatal "certificat de test"
printf '{ "demo": false, "environnement": "recette-qa-v8" }\n' > "$T/etc/config.json"
hote_back="${BACK#http://}"
sed -e "s#server ged-app.marchica.local:8080;#server $hote_back;#" \
    -e "s#listen 80;#listen $HP;#" -e "s#listen \[::\]:80;#listen [::]:$HP;#" \
    -e "s#listen 443 ssl http2;#listen $HS ssl http2;#" -e "s#listen \[::\]:443 ssl http2;#listen [::]:$HS ssl http2;#" \
    -e "s#/etc/nginx/tls/#$T/tls/#g" -e "s#root /opt/ged/front/courant;#root $PAQUET;#" \
    -e "s#alias /etc/ged/front/config.json;#alias $T/etc/config.json;#" \
    -e "s#/etc/nginx/ged/#$T/ged/#g" \
    -e "s#/var/log/nginx/#$T/logs/#g" "$CONF" > "$T/ged.conf"
# Tour 3 (réserve IPv6 de T-006, 4caa7a0) : ged.conf ne doit plus écouter en IPv6 que par les fichiers
# facultatifs ecoute-ipv6-*.conf (inclus par motif depuis /etc/nginx/ged/, ici $T/ged/). Plus aucune
# ligne n'est retirée de la configuration livrée.
rm -rf "$T/ged"; mkdir -p "$T/ged"
if grep -Eq '^[[:space:]]*listen[[:space:]]+\[::' "$T/ged.conf"; then
  resultat N00 ECHEC "ged.conf écoute encore en IPv6 sans condition" "$(grep -E '^[[:space:]]*listen[[:space:]]+\[::' "$T/ged.conf" | tr '\n' ' ')"
else
  resultat N00 OK "ged.conf livré sans écoute IPv6 inconditionnelle (écoutes [::] dans les fichiers facultatifs ecoute-ipv6-*.conf) [2.2]" "IPv6 sur ce poste : $([[ -e /proc/net/if_inet6 ]] && echo oui || echo non)"
fi
diff "$CONF" "$T/ged.conf" > "$T/adaptations.diff"
cat > "$T/nginx.conf" <<EOF
user root;
worker_processes 1;
pid $T/nginx.pid;
error_log $T/logs/error.log warn;
events { worker_connections 256; }
http {
  include /etc/nginx/mime.types;
  default_type application/octet-stream;
  sendfile on;
  client_body_temp_path $T/temp;
  access_log $T/logs/access.log;
  include $T/ged.conf;
}
EOF
info "adaptations de ged.conf (seules les valeurs « À ADAPTER » et les ports) :"; sed 's/^/#   /' "$T/adaptations.diff"
# Variante « IPv6 » : fichiers facultatifs installés (ports adaptés). nginx -t ouvre les sockets :
# sur un hôte sans IPv6, la syntaxe est acceptée et l'ouverture refusée (errno 97), comme le démarrage.
for f in ecoute-ipv6-http.conf ecoute-ipv6-https.conf; do
  sed -e "s#listen \[::\]:80;#listen [::]:$HP;#" -e "s#listen \[::\]:443 ssl http2;#listen [::]:$HS ssl http2;#" \
      "$DEPOT_RACINE/deploiement/nginx/$f" > "$T/ged/$f"
done
$N -t > "$T/nginx-t-ipv6.log" 2>&1; c6=$?
if [[ -e /proc/net/if_inet6 ]]; then
  [[ $c6 == 0 ]] && resultat N01b OK "nginx -t avec les écoutes IPv6 facultatives (hôte IPv6)" "$(tr '\n' ' ' < "$T/nginx-t-ipv6.log" | cut -c1-200)" \
    || resultat N01b ECHEC "nginx -t avec les écoutes IPv6 facultatives" "$(tr '\n' ' ' < "$T/nginx-t-ipv6.log" | cut -c1-300)"
else
  [[ $c6 != 0 ]] && grep -q "syntax is ok" "$T/nginx-t-ipv6.log" && grep -q "(97" "$T/nginx-t-ipv6.log" \
    && resultat N01b OK "Avec les écoutes IPv6 facultatives sur un hôte sans IPv6 : syntaxe acceptée, ouverture refusée (errno 97) — ne pas installer ces fichiers (EXPLOITATION.md §3)" "$(grep -o 'listen.*(97[^)]*)' "$T/nginx-t-ipv6.log" | head -1 | cut -c1-160)" \
    || resultat N01b ECHEC "Écoutes IPv6 facultatives : résultat inattendu de nginx -t" "code $c6 $(tr '\n' ' ' < "$T/nginx-t-ipv6.log" | cut -c1-300)"
fi
rm -f "$T/ged"/ecoute-ipv6-*.conf "$T/nginx.pid"   # essai nominal : configuration livrée telle quelle
if $N -t > "$T/nginx-t.log" 2>&1; then resultat N01 OK "nginx -t : configuration livrée acceptée par NGINX $(nginx -v 2>&1 | cut -d/ -f2)" "$(tr '\n' ' ' < "$T/nginx-t.log" | cut -c1-200)"
else resultat N01 ECHEC "nginx -t" "$(tr '\n' ' ' < "$T/nginx-t.log" | cut -c1-300)"; bilan "NGINX réel"; exit 1; fi
$N || fatal "démarrage de NGINX"; sleep 1
H="https://ged.marchica.ma:$HS"; R=(--resolve "ged.marchica.ma:$HS:127.0.0.1" --resolve "ged.marchica.ma:$HP:127.0.0.1" -k -s --noproxy "*")

# --- N02 redirection ---------------------------------------------------------------------------
e=$(curl "${R[@]}" -o /dev/null -D - "http://ged.marchica.ma:$HP/documents?x=1" | tr -d '\r')
code=$(head -1 <<<"$e" | awk '{print $2}'); loc=$(grep -i '^location:' <<<"$e" | cut -d' ' -f2); srv=$(grep -i '^server:' <<<"$e" | cut -d' ' -f2-)
[[ "$code" == 301 && "$loc" == "https://ged.marchica.ma/documents?x=1" && "$srv" == nginx ]] \
  && resultat N02 OK "HTTP → 301 vers HTTPS (chemin et requête conservés), Server sans version [6.2.2]" "$code $loc ; Server: $srv" \
  || resultat N02 ECHEC "HTTP → 301 vers HTTPS, Server sans version" "$code $loc ; Server: $srv"

# --- N03 versions TLS --------------------------------------------------------------------------
tls() { echo | timeout 5 openssl s_client -connect "127.0.0.1:$HS" -servername ged.marchica.ma "$1" -cipher 'DEFAULT:@SECLEVEL=0' 2>&1 | grep -E '^ *Protocol *:|Cipher is|no protocols available|alert|handshake failure' | head -2 | tr '\n' ' '; }
t10=$(tls -tls1); t11=$(tls -tls1_1); t12=$(tls -tls1_2); t13=$(tls -tls1_3)
ok10=$([[ "$t10" != *"TLSv1 "* && "$t10" != *"Cipher is ECDHE"* ]] && echo 1); ok11=$([[ "$t11" != *"TLSv1.1"* || "$t11" == *"Cipher is (NONE)"* ]] && echo 1)
[[ "$ok10" == 1 && "$ok11" == 1 && "$t12" == *"TLSv1.2"* && "$t13" == *"TLSv1.3"* ]] \
  && resultat N03 OK "TLS 1.0 et 1.1 refusés, TLS 1.2 et 1.3 acceptés [6.2.1]" "1.0 : ${t10:0:80} | 1.1 : ${t11:0:80} | 1.2 : ${t12:0:70} | 1.3 : ${t13:0:70}" \
  || resultat N03 ECHEC "Versions TLS" "1.0 : $t10 | 1.1 : $t11 | 1.2 : $t12 | 1.3 : $t13"

# --- N04 en-têtes de sécurité --------------------------------------------------------------------
manque=(); for u in "/" "/api/v1/documents" "/inexistant-$RANDOM.html" "/api/v1/documents/00000000-0000-0000-0000-000000000000"; do
  e=$(curl "${R[@]}" -o /dev/null -D - "$H$u" | tr -d '\r'); c=$(head -1 <<<"$e" | awk '{print $2}')
  for h in Strict-Transport-Security X-Content-Type-Options X-Frame-Options Content-Security-Policy Referrer-Policy; do
    n=$(grep -ci "^$h:" <<<"$e"); [[ "$n" == 1 ]] || manque+=("$u($c) $h×$n")
  done
  grep -qi '^server: nginx$' <<<"$e" || manque+=("$u Server")
done
[[ ${#manque[@]} -eq 0 ]] && resultat N04 OK "En-têtes HSTS, nosniff, X-Frame-Options, CSP, Referrer-Policy présents une seule fois sur page, API (401/404) et erreur NGINX [6.2.2]" "" \
  || resultat N04 ECHEC "En-têtes de sécurité absents ou en double" "${manque[*]}"

# --- N05 paquet Angular ----------------------------------------------------------------------------
e=$(curl "${R[@]}" -D - "$H/" -o "$T/index.html" | tr -d '\r'); c1=$(head -1 <<<"$e" | awk '{print $2}'); cc1=$(grep -i '^cache-control:' <<<"$e" | cut -d' ' -f2-)
js=$(grep -o 'src="[^"]*\.js"' "$T/index.html" | head -1 | cut -d'"' -f2)
e=$(curl "${R[@]}" -H 'Accept-Encoding: gzip' -o /dev/null -D - "$H/$js" | tr -d '\r'); c2=$(head -1 <<<"$e" | awk '{print $2}'); cc2=$(grep -i '^cache-control:' <<<"$e" | cut -d' ' -f2-); gz=$(grep -i '^content-encoding:' <<<"$e" | cut -d' ' -f2)
cfg=$(curl "${R[@]}" "$H/assets/config.json" | tr -d ' \n'); cfgp=$(tr -d ' \n' < "$PAQUET/assets/config.json")
c404=$(curl "${R[@]}" -o /dev/null -w '%{http_code}' "$H/chemin/inconnu"); cgit=$(curl "${R[@]}" -o /dev/null -w '%{http_code}' "$H/.git/config")
cenv=$(curl "${R[@]}" -o /dev/null -w '%{http_code}' "$H/.env"); cact=$(curl "${R[@]}" -o /dev/null -w '%{http_code}' "$H/actuator/prometheus")
[[ "$c1" == 200 && "$cc1" == no-cache && "$c2" == 200 && "$cc2" == "max-age=2592000" && "$cfg" == *recette-qa-v8* && "$c404" == 404 && "$cgit" == 404 && "$cenv" == 404 && "$cact" == 404 ]] \
  && resultat N05 OK "Paquet Angular servi par NGINX ; index.html sans cache, fichiers compilés en cache 30 j ; config.json d'environnement servi à la place de celui du paquet ; chemin inconnu, fichiers cachés, Actuator : 404 [2.2, 10.1, 6.2.3 A05]" \
      "index $c1 ($cc1), $js $c2 ($cc2, $gz), config.json $cfg (paquet : $cfgp), inconnu $c404, .git $cgit, .env $cenv, actuator $cact" \
  || resultat N05 ECHEC "Paquet Angular et config.json" "index $c1 ($cc1), $js $c2 ($cc2), config.json $cfg, inconnu $c404, .git $cgit, .env $cenv, actuator $cact"

# --- N06 API par NGINX, adresse du client ---------------------------------------------------------
corps="{\"identifiant\":\"$ID\",\"motDePasse\":\"$MDP\"}"
e=$(curl "${R[@]}" --interface 127.0.0.2 -H 'Content-Type: application/json' -H 'X-Forwarded-For: 6.6.6.6' -H 'X-Requested-With: XMLHttpRequest' \
      -D - -o "$T/login.json" --data "$corps" "$H/api/v1/auth/login" | tr -d '\r')
c=$(head -1 <<<"$e" | awk '{print $2}')
if [[ -n "${GED_SQL:-}" ]]; then
  ip=$($GED_SQL "SELECT adresse_ip FROM session ORDER BY cree_le DESC LIMIT 1" | tr -d ' ')
else ip="(GED_SQL absent)"; fi
[[ "$c" == 200 && "$ip" == 127.0.0.2 ]] \
  && resultat N06 OK "Connexion par NGINX : adresse du client (127.0.0.2) enregistrée par le back-end, X-Forwarded-For forgé (6.6.6.6) écrasé [3.4.1, 7.4.1, ANO-E2-001]" "HTTP $c, session.adresse_ip=$ip" \
  || resultat N06 ECHEC "Adresse du client derrière NGINX" "HTTP $c, session.adresse_ip=$ip (attendu 127.0.0.2)"
JETON=$(grep -o '"token":"[^"]*"' "$T/login.json" | cut -d'"' -f4)

# --- N07 limitation de débit ----------------------------------------------------------------------
codes=""; for i in $(seq 1 10); do codes+="$(curl "${R[@]}" --interface 127.0.0.3 -o /dev/null -w '%{http_code}' "$H/api/v1/auth/me") "; done
n429=$(grep -o 429 <<<"$codes" | wc -l)
[[ "$codes" == "401 401 401 401 401 401 429 429 429 429 " ]] \
  && resultat N07 OK "limit_req sur /api/v1/auth/ : 1 + burst 5 passent, puis 429 par NGINX [6.2.2]" "$codes" \
  || resultat N07 $([[ $n429 -gt 0 ]] && echo AVERT || echo ECHEC) "limit_req sur /api/v1/auth/" "$codes"

# --- N08 taille des corps -------------------------------------------------------------------------
# Fichiers TEXTE (type réel text/plain) : seul le critère de taille peut les refuser.
texte() { yes "zarkolinet ligne de recette qa v8 pour la taille des corps" | head -c "$1" > "$2"; }
texte $((211 * 1024 * 1024)) "$T/gros-211.txt"; texte $((205 * 1024 * 1024)) "$T/gros-205.txt"
r211=$(curl "${R[@]}" -o "$T/r211" -w '%{http_code}' -H "Authorization: Bearer $JETON" -H "Idempotency-Key: $(uuid_aleatoire)" \
        -F "file=@$T/gros-211.txt;filename=gros.txt" -F "name=qav8-211" -F "typeDocumentId=${GED_TYPE_DOCUMENT_TEXTE_ID:-x}" "$H/api/v1/documents")
srv211=$(grep -o '<center>nginx</center>' "$T/r211" | head -1)
r205=$(curl "${R[@]}" -o "$T/r205" -D "$T/h205" -w '%{http_code}' -H "Authorization: Bearer $JETON" -H "Idempotency-Key: $(uuid_aleatoire)" \
        -F "file=@$T/gros-205.txt;filename=gros.txt" -F "name=qav8-205" -F "typeDocumentId=${GED_TYPE_DOCUMENT_TEXTE_ID:-x}" "$H/api/v1/documents")
code205=$(grep -o '"code":"[A-Z_]*"' "$T/r205" | head -1); ct205=$(grep -i '^content-type:' "$T/h205" | tr -d '\r' | cut -d' ' -f2-)
[[ "$r211" == 413 && -n "$srv211" ]] \
  && resultat N08a OK "Corps > 210 Mo : 413 de NGINX (client_max_body_size), le back-end n'est pas sollicité [6.1.5, 6.2.2]" "211 Mo → $r211 (page NGINX)" \
  || resultat N08a ECHEC "Corps > 210 Mo refusé par NGINX" "211 Mo → $r211 ($srv211)"
[[ "$r205" == 413 && "$ct205" == application/problem+json* ]] \
  && resultat N08b OK "Fichier de 205 Mo (sous 210 Mo) : transmis par NGINX, 413 problem+json du back-end (plafond 200 Mo) [6.1.5, 5.3.2]" "205 Mo → $r205 $code205" \
  || resultat N08b ECHEC "Fichier de 205 Mo : 413 problem+json attendu du back-end (plafond de plateforme 200 Mo)" "205 Mo → $r205 ($ct205) $(head -c 160 "$T/r205")"

# --- N09 aucun tampon disque de NGINX pendant un dépôt ---------------------------------------------
texte $((150 * 1024 * 1024)) "$T/depot-150.txt"; : > "$T/temp-observes.txt"
( curl "${R[@]}" -o "$T/r150" -w '%{http_code}' -H "Authorization: Bearer $JETON" -H "Idempotency-Key: $(uuid_aleatoire)" \
    -F "file=@$T/depot-150.txt;filename=depot.txt" -F "name=qav8-150" -F "typeDocumentId=${GED_TYPE_DOCUMENT_TEXTE_ID:-x}" "$H/api/v1/documents" > "$T/c150" ) &
p=$!; nb=0; while kill -0 $p 2>/dev/null; do find "$T/temp" -type f >> "$T/temp-observes.txt" 2>/dev/null; nb=$((nb + 1)); sleep 0.1; done
nt=$(sort -u "$T/temp-observes.txt" | grep -c .)
[[ "$nt" == 0 && "$(cat "$T/c150")" =~ ^20[12]$ ]] \
  && resultat N09 OK "Dépôt de 150 Mo par NGINX : aucun fichier dans client_body_temp pendant le transfert (proxy_request_buffering off) [6.1]" "HTTP $(cat "$T/c150"), $nb relevés, fichiers temporaires observés : 0" \
  || resultat N09 ECHEC "Tampon disque de NGINX pendant un dépôt" "HTTP $(cat "$T/c150"), fichiers temporaires observés : $nt $(sort -u "$T/temp-observes.txt" | head -2 | tr '\n' ' ')"
rm -f "$T"/gros-*.txt "$T/depot-150.txt"

# --- N10 traceparent ------------------------------------------------------------------------------
# La connexion de N06 est auditée (trace_id) : l'identifiant créé par NGINX doit s'y retrouver.
tr=$(grep 'POST /api/v1/auth/login' "$T/logs/ged.access.log" | tail -1 | grep -o 'trace=00-[0-9a-f]*' | cut -d- -f2)
if [[ -n "${GED_SQL:-}" && -n "$tr" ]]; then
  ev=$($GED_SQL "SELECT action FROM journal_audit WHERE trace_id = '$tr'" | tr '\n' ' ')
  n=0; [[ -n "${GED_JOURNAL_BACKEND:-}" ]] && n=$(grep -c "$tr" "$GED_JOURNAL_BACKEND")
  [[ -n "$ev" ]] && resultat N10 OK "traceparent créé par NGINX = trace_id de l'audit du back-end [7.3, 7.4.1]" "trace $tr : audit « $ev», journal technique $n ligne(s)" \
    || resultat N10 ECHEC "traceparent créé par NGINX absent de l'audit du back-end" "trace $tr"
else resultat N10 NA "traceparent" "GED_SQL ou trace NGINX absent"; fi

arreter
bilan "T-006 / T-066 NGINX réel ($(nginx -v 2>&1 | cut -d/ -f2))"
