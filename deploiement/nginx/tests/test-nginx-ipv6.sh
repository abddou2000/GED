#!/usr/bin/env bash
# =====================================================================
#  Test de ged.conf avec et sans écoute IPv6 (T-006, DAT 2.2) contre un
#  NGINX réel. ged.conf livré, adapté seulement pour l'essai : ports non
#  privilégiés, certificat auto-signé, back-end fictif, chemins locaux,
#  répertoire /etc/nginx/ged/ remplacé par un répertoire de travail.
#    V1  sans les fichiers ecoute-ipv6-*.conf : nginx -t, démarrage, 301
#    V2  avec les fichiers ecoute-ipv6-*.conf : nginx -t ; démarrage et 301
#        en [::1] si l'hôte a IPv6 ; sinon refus attendu (errno 97) constaté
#        et démarrage en [::1] signalé « NON APPLICABLE » (hôte sans IPv6)
#  Usage : test-nginx-ipv6.sh
#  Code de sortie : 0 si RÉUSSI (y compris avec la partie IPv6 non applicable,
#  signalée dans le résultat) ; 1 si un contrôle échoue ; 2 si NGINX absent.
#  Le répertoire de travail ($TMPDIR/ged-nginx.*) est supprimé dans tous les cas.
# =====================================================================
set -Eeuo pipefail
ICI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NGX="$(cd "$ICI/.." && pwd)"
command -v nginx >/dev/null || { echo "NGINX absent du poste"; exit 2; }
HP="${GED_TEST_NGINX_HTTP:-18382}"; HS="${GED_TEST_NGINX_HTTPS:-18482}"
T="$(mktemp -d "${TMPDIR:-/tmp}/ged-nginx.XXXXXX")"
# Arrête le NGINX lancé par l'essai. Un nginx.pid vide (laissé par `nginx -t`)
# ou périmé n'est pas une erreur : rien à arrêter (ANO-E10-009).
arreter() {
    local pid=""
    [[ -s "$T/nginx.pid" ]] && pid="$(< "$T/nginx.pid")"
    if [[ "$pid" =~ ^[0-9]+$ ]] && kill "$pid" 2>/dev/null; then
        for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$pid" 2>/dev/null || break; sleep 0.2; done
    fi
    rm -f "$T/nginx.pid"
    return 0
}
# Le nettoyage ne change jamais le verdict : le code de sortie reste celui du script.
nettoyer() { local rc=$?; set +e; arreter; rm -rf "$T"; exit "$rc"; }
trap nettoyer EXIT
mkdir -p "$T"/{tls,logs,etc,temp,front,ged}
ECHECS=0
NON_APPLICABLE=""
ok() { echo "  [OK]    $*"; }
ko() { echo "  [ÉCHEC] $*"; ECHECS=$((ECHECS + 1)); }
na() { echo "  [N/A]   $*"; NON_APPLICABLE="${NON_APPLICABLE:+$NON_APPLICABLE ; }$*"; }

openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=ged.marchica.ma" \
    -keyout "$T/tls/ged.key" -out "$T/tls/ged.fullchain.pem" 2>/dev/null
echo '<!doctype html><title>GED</title>' > "$T/front/index.html"
echo '{ "demo": false }' > "$T/etc/config.json"
adapter() {  # fichier → sortie adaptée à l'essai
    sed -e "s#server ged-app.marchica.local:8080;#server 127.0.0.1:9;#" \
        -e "s#listen 80;#listen 127.0.0.1:$HP;#" -e "s#listen \[::\]:80;#listen [::1]:$HP;#" \
        -e "s#listen 443 ssl http2;#listen 127.0.0.1:$HS ssl http2;#" \
        -e "s#listen \[::\]:443 ssl http2;#listen [::1]:$HS ssl http2;#" \
        -e "s#/etc/nginx/ged/#$T/ged/#g" -e "s#/etc/nginx/tls/#$T/tls/#g" \
        -e "s#root /opt/ged/front/courant;#root $T/front;#" \
        -e "s#alias /etc/ged/front/config.json;#alias $T/etc/config.json;#" \
        -e "s#/var/log/nginx/#$T/logs/#g" "$1"
}
adapter "$NGX/ged.conf" > "$T/ged.conf"
grep -Eq '^[[:space:]]*listen[[:space:]]+\[::' "$T/ged.conf" && ko "ged.conf contient encore un listen IPv6 inconditionnel" \
    || ok "ged.conf n'écoute en IPv6 que par les fichiers facultatifs"
cat > "$T/nginx.conf" <<EOF
user root;
worker_processes 1;
pid $T/nginx.pid;
error_log $T/logs/error.log warn;
events { worker_connections 64; }
http {
  include /etc/nginx/mime.types;
  default_type application/octet-stream;
  client_body_temp_path $T/temp;
  access_log $T/logs/access.log;
  include $T/ged.conf;
}
EOF
N=(nginx -p "$T" -c "$T/nginx.conf")
R=(-s -o /dev/null -w '%{http_code}' --noproxy '*' --max-time 5)

echo "V1 — sans écoute IPv6"
if "${N[@]}" -t > "$T/t1.log" 2>&1; then ok "nginx -t"; else ko "nginx -t : $(tr '\n' ' ' < "$T/t1.log")"; fi
if "${N[@]}" > "$T/d1.log" 2>&1; then
    sleep 0.5
    code="$(curl "${R[@]}" -H 'Host: ged.marchica.ma' "http://127.0.0.1:$HP/")"
    [[ "$code" == 301 ]] && ok "démarrage, HTTP → 301" || ko "HTTP : $code"
    code="$(curl "${R[@]}" -k --resolve "ged.marchica.ma:$HS:127.0.0.1" "https://ged.marchica.ma:$HS/")"
    [[ "$code" == 200 ]] && ok "HTTPS → 200 (paquet servi)" || ko "HTTPS : $code"
else
    ko "démarrage : $(tr '\n' ' ' < "$T/d1.log")"
fi
arreter

echo "V2 — avec écoute IPv6 (ecoute-ipv6-http.conf, ecoute-ipv6-https.conf)"
for f in ecoute-ipv6-http.conf ecoute-ipv6-https.conf; do adapter "$NGX/$f" > "$T/ged/$f"; done
if [[ -e /proc/net/if_inet6 ]]; then
    if "${N[@]}" -t > "$T/t2.log" 2>&1; then ok "nginx -t"; else ko "nginx -t : $(tr '\n' ' ' < "$T/t2.log")"; fi
    if "${N[@]}" > "$T/d2.log" 2>&1; then
        sleep 0.5
        code="$(curl "${R[@]}" -g -H 'Host: ged.marchica.ma' "http://[::1]:$HP/")"
        [[ "$code" == 301 ]] && ok "hôte IPv6 : démarrage, HTTP [::1] → 301" || ko "HTTP [::1] : $code"
    else
        ko "démarrage : $(tr '\n' ' ' < "$T/d2.log")"
    fi
    arreter
else
    # nginx -t ouvre aussi les sockets : sur un hôte sans IPv6, la syntaxe est
    # acceptée mais le test échoue sur errno 97, comme le démarrage.
    "${N[@]}" -t > "$T/t2.log" 2>&1 && code=0 || code=$?
    rm -f "$T/nginx.pid"   # nginx -t laisse un nginx.pid vide : aucun processus à arrêter
    grep -q "syntax is ok" "$T/t2.log" && ok "nginx -t : syntaxe acceptée" || ko "nginx -t : $(tr '\n' ' ' < "$T/t2.log")"
    if [[ $code -ne 0 ]] && grep -q "(97" "$T/t2.log"; then
        ok "hôte sans IPv6 : refus attendu constaté (errno 97) — d'où les fichiers facultatifs"
    else
        ko "hôte sans IPv6 : résultat inattendu de nginx -t : $(tr '\n' ' ' < "$T/t2.log")"
    fi
    na "démarrage et HTTP [::1] → 301 : hôte sans IPv6 (/proc/net/if_inet6 absent), à éprouver sur un hôte IPv6"
fi

if (( ECHECS > 0 )); then echo "RÉSULTAT : $ECHECS contrôle(s) en échec"; exit 1; fi
if [[ -n "$NON_APPLICABLE" ]]; then
    echo "RÉSULTAT : RÉUSSI — NON APPLICABLE sur cet hôte : $NON_APPLICABLE"
else
    echo "RÉSULTAT : RÉUSSI"
fi
exit 0
