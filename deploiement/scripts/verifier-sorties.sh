#!/usr/bin/env bash
# =====================================================================
#  verifier-sorties.sh — cohérence entre les destinations de ged.env et le
#  filtre de sortie systemd (P-12, OWASP A10 ; ANO-E11-002).
#
#  Le filtre (ged-backend.service.d/sorties.conf) n'accepte que des adresses ;
#  ged.env désigne la base, l'annuaire, le relais et clamd par noms d'hôtes.
#  Ce script résout chaque nom COMME LA JVM (getent : /etc/hosts puis DNS,
#  selon nsswitch) et vérifie que chaque adresse obtenue est autorisée ; il
#  vérifie aussi que les résolveurs de /etc/resolv.conf le sont, sauf si tous
#  les noms sont figés dans /etc/hosts.
#
#  Usage :
#    verifier-sorties.sh [ged.env] [sorties.conf]
#  Défauts : /etc/ged/ged.env et
#            /etc/systemd/system/ged-backend.service.d/sorties.conf
#  Code de sortie : 0 si tout est autorisé, 1 sinon (chaque écart est listé).
#
#  À lancer à l'installation, à chaque changement d'adresse et à chaque ajout
#  de contrôleur de domaine dans GED_LDAP_URLS.
# =====================================================================
set -Eeuo pipefail

ENV_GED="${1:-/etc/ged/ged.env}"
SORTIES="${2:-/etc/systemd/system/ged-backend.service.d/sorties.conf}"
# Surcharges (essais sur un poste sans getent) : résolution et fichiers système.
GETENT="${GETENT:-getent}"
RESOLV_CONF="${RESOLV_CONF:-/etc/resolv.conf}"
HOSTS="${HOSTS:-/etc/hosts}"

[[ -r "$ENV_GED" ]] || { echo "ECHEC : $ENV_GED illisible" >&2; exit 1; }
[[ -r "$SORTIES" ]] || { echo "ECHEC : $SORTIES illisible" >&2; exit 1; }

valeur() { # valeur d'une variable de ged.env, guillemets retirés
    sed -n "s/^[[:space:]]*$1=//p" "$ENV_GED" | tail -n 1 | sed -e 's/^["'\'']//' -e 's/["'\'']$//'
}

# Adresses et réseaux autorisés (IPAddressAllow, lignes non commentées).
autorises="$(sed -n 's/^[[:space:]]*IPAddressAllow=//p' "$SORTIES" | tr ' ' '\n' | sed '/^$/d' \
    | sed -e 's#^localhost$#127.0.0.0/8\n::1/128#' -e 's#^any$#0.0.0.0/0\n::/0#')"

# Vrai si l'adresse $1 est couverte par un élément de $autorises.
autorisee() {
    printf '%s\n' "$autorises" | awk -v ip="$1" '
        function v4(a,   p, n) { n = split(a, p, "."); if (n != 4) return -1
            return ((p[1] * 256 + p[2]) * 256 + p[3]) * 256 + p[4] }
        BEGIN { trouve = 0 }
        {
            reseau = $0; long = -1
            if (index(reseau, "/")) { split(reseau, r, "/"); reseau = r[1]; long = r[2] + 0 }
            if (index(ip, ":") || index(reseau, ":")) {
                # IPv6 : égalité exacte, ou ::1 pour la boucle locale.
                if (ip == reseau) trouve = 1
                next
            }
            a = v4(ip); b = v4(reseau)
            if (a < 0 || b < 0) next
            if (long < 0) long = 32
            bloc = 2 ^ (32 - long)
            if (int(a / bloc) == int(b / bloc)) trouve = 1
        }
        END { exit trouve ? 0 : 1 }'
}

# Noms à contrôler : base, contrôleurs de domaine, relais SMTP, clamd.
noms=()
for v in DB_HOST GED_SMTP_HOTE GED_CLAMAV_HOTE; do
    n="$(valeur "$v")"; [[ -n "$n" ]] && noms+=("$v=$n")
done
IFS=',' read -r -a urls <<< "$(valeur GED_LDAP_URLS)"
for u in "${urls[@]}"; do
    h="$(printf '%s' "$u" | sed -e 's#^[[:space:]]*[a-zA-Z]*://##' -e 's#[:/].*$##')"
    [[ -n "$h" ]] && noms+=("GED_LDAP_URLS=$h")
done

ecarts=0
tous_figes=1
for entree in "${noms[@]}"; do
    var="${entree%%=*}"; nom="${entree#*=}"
    grep -Eq "^[^#]*[[:space:]]$nom([[:space:]]|\$)" "$HOSTS" 2>/dev/null || tous_figes=0
    adresses="$("$GETENT" ahosts "$nom" 2>/dev/null | awk '{print $1}' | sort -u || true)"
    if [[ -z "$adresses" ]]; then
        echo "ECHEC  $var : « $nom » ne se résout pas"; ecarts=$((ecarts + 1)); continue
    fi
    for ip in $adresses; do
        if autorisee "$ip"; then
            echo "OK     $var : $nom -> $ip"
        else
            echo "ECHEC  $var : $nom -> $ip, absente de IPAddressAllow"; ecarts=$((ecarts + 1))
        fi
    done
done

# Résolveurs : nécessaires dès qu'un nom n'est pas figé dans /etc/hosts.
if [[ "$tous_figes" -eq 0 ]]; then
    for ns in $(sed -n 's/^[[:space:]]*nameserver[[:space:]]\{1,\}//p' "$RESOLV_CONF" 2>/dev/null); do
        if autorisee "$ns"; then
            echo "OK     résolveur DNS $ns"
        else
            echo "ECHEC  résolveur DNS $ns absent de IPAddressAllow (ou figer les noms dans $HOSTS)"
            ecarts=$((ecarts + 1))
        fi
    done
else
    echo "OK     tous les noms sont figés dans $HOSTS : aucun résolveur requis"
fi

if [[ "$ecarts" -gt 0 ]]; then
    echo "$ecarts destination(s) bloquée(s) par le filtre de sortie : corriger $SORTIES ou $ENV_GED" >&2
    exit 1
fi
echo "Filtre de sortie cohérent avec $ENV_GED"
