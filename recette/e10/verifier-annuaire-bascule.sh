#!/usr/bin/env bash
# Recette P-02 (DAT 3.3, revue D4) et P-04 (DAT 3.4.2, revue D1) : liaison à l'annuaire avec
# PLUSIEURS contrôleurs, contre une instance dont GED_LDAP_URLS désigne, dans l'ordre :
#   1. un contrôleur MUET (accepte TCP, ne répond jamais) : délai de lecture puis bascule ;
#   2. et 3. deux contrôleurs simulés autonomes (recette/e10/AnnuaireAutonome.java), que ce script
#      arrête et relance (commandes passées en variables) pendant que l'application tourne.
#
#   A01 un contrôleur muet en tête : connexion réussie par bascule, dans le délai de lecture (5 s)
#       + marge ; métrique ged_annuaire_controleur par contrôleur ; sonde « annuaire » DEGRADE ;
#   A02 le muet arrêté (port fermé, connexion TCP refusée) : connexion par le suivant ;
#   A03 contrôleur 2 arrêté : connexion toujours possible (bascule vers le 3) ;
#   A04 tous arrêtés : connexion refusée sans mode dégradé ; session ouverte conservée
#       (jeton d'accès et renouvellement) ; sonde DOWN, readiness non affectée (DAT 3.3) ;
#   A05 contrôleur relancé : connexions de nouveau possibles, sans redémarrer l'application ;
#   P04 compte désactivé dans l'annuaire (userAccountControl 514) : connexion refusée (D1) ;
#       réactivé : connexion rétablie avec les mêmes rôles.
#
# Variables : GED_URL, GED_URL_SANTE, GED_RECETTE_MOT_DE_PASSE, GED_V8_COMPTE (défaut kelfassi),
#   GED_V8_ARRETER_MUET, GED_V8_ARRETER_2, GED_V8_ARRETER_3, GED_V8_LANCER_2 (commandes), GED_V8_LDAP_2
#   (hôte:port des contrôleurs simulés, pour la désactivation du compte), GED_V8_CP (classpath Java
#   avec UnboundID, pour la modification LDAP).
source "$(dirname "${BASH_SOURCE[0]}")/../lib/commun.sh"
U="${GED_URL:-http://localhost:18084}"; S="${GED_URL_SANTE:-http://localhost:18094}"
MDP="${GED_RECETTE_MOT_DE_PASSE:?}"; C="${GED_V8_COMPTE:-kelfassi}"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
connexion() {  # connexion COMPTE [ip-source] → code HTTP ; corps dans $T/login.json ; durée dans $DUREE
  local d0; d0=$(date +%s%N)
  CODE=$(curl -s --noproxy '*' --interface "${2:-127.0.0.1}" -o "$T/login.json" -c "$T/cookies" -w '%{http_code}' -H 'Content-Type: application/json' \
      --data "{\"identifiant\":\"$1\",\"motDePasse\":\"$MDP\"}" "$U/api/v1/auth/login")
  DUREE=$(( ($(date +%s%N) - d0) / 1000000 ))
}
jeton() { grep -o '"token":"[^"]*"' "$T/login.json" | cut -d'"' -f4; }
sonde() { curl -s --noproxy '*' "$S/actuator/health" | grep -o '"annuaire":{[^}]*' | grep -o '"status":"[A-Z]*"' | cut -d'"' -f4; }
metriques() { curl -s --noproxy '*' "$S/actuator/prometheus" | grep '^ged_annuaire_controleur' | sed 's/.*controleur="\([^"]*\)".*} \(.*\)/\1=\2/' | tr '\n' ' '; }
ip=10   # chaque connexion part d'une adresse de bouclage différente : la limitation par IP (5/min) ne gêne pas
prochaine_ip() { ip=$((ip + 1)); echo "127.0.0.$ip"; }

# A01 : contrôleur muet en tête (accepte TCP, ne répond jamais)
sleep 31   # la sonde garde son résultat 30 s
# Trois connexions successives : la première peut basculer en ouvrant la connexion du compte de
# service, les suivantes doivent basculer aussi (liaison de l'utilisateur).
essais=""; c1=200; d1=0
for k in 1 2 3; do
  connexion "$C" "$(prochaine_ip)"; essais+="$CODE/${DUREE}ms "; [[ "$CODE" != 200 ]] && { c1="$CODE"; corps1="$(head -c 200 "$T/login.json")"; }
  (( DUREE > d1 )) && d1=$DUREE; sleep 3
done
corps1="${corps1:-} ; essais $essais"
m="$(metriques)"; s="$(sonde)"
[[ "$c1" == 200 && $d1 -lt 12000 && "$m" == *"=0.0"* && "$s" == DEGRADE ]] \
  && resultat A01 OK "Contrôleur muet en tête : connexion par bascule vers le suivant ; métrique par contrôleur ; sonde DEGRADE [3.3, D4]" "HTTP $c1 en $d1 ms ; $m; sonde $s" \
  || resultat A01 ECHEC "Contrôleur muet en tête : la connexion doit basculer vers le contrôleur suivant (D4)" "HTTP $c1 en $d1 ms ($corps1) ; $m; sonde $s"

# A02 : le muet s'arrête (port fermé : connexion refusée) → bascule
eval "$GED_V8_ARRETER_MUET"; sleep 1
connexion "$C" "$(prochaine_ip)"; J1="$(jeton)"; cp "$T/cookies" "$T/cookies-session"
[[ "$CODE" == 200 ]] && resultat A02 OK "Premier contrôleur arrêté (connexion TCP refusée) : connexion par le suivant [3.3, D4]" "HTTP $CODE en $DUREE ms" \
  || resultat A02 ECHEC "Premier contrôleur arrêté : bascule vers le suivant" "HTTP $CODE en $DUREE ms $(head -c 200 "$T/login.json")"

# A03 : deuxième contrôleur arrêté → troisième
eval "$GED_V8_ARRETER_2"; sleep 1
connexion "$C" "$(prochaine_ip)"
[[ "$CODE" == 200 ]] && resultat A03 OK "Deuxième contrôleur arrêté : connexion par le troisième [3.3, D4]" "HTTP $CODE en $DUREE ms" \
  || resultat A03 ECHEC "Deuxième contrôleur arrêté : bascule vers le troisième" "HTTP $CODE en $DUREE ms $(head -c 200 "$T/login.json")"

# A04 : tous arrêtés
eval "$GED_V8_ARRETER_3"; sleep 31
connexion "${GED_V8_COMPTE_2:-yalaoui}" "$(prochaine_ip)"; c3="$CODE"; d3="$DUREE"; corps3="$(head -c 250 "$T/login.json")"
me=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $J1" "$U/api/v1/auth/me")
ren=$(curl -s --noproxy '*' -o "$T/ren.json" -w '%{http_code}' -b "$T/cookies-session" -X POST -H 'X-GED-Renouvellement: 1' "$U/api/v1/auth/refresh")
s="$(sonde)"; r=$(curl -s --noproxy '*' "$S/actuator/health/readiness" | grep -o '"status":"[A-Z]*"' | head -1 | cut -d'"' -f4)
[[ "$c3" == 503 && "$me" == 200 && "$ren" == 200 && "$s" == DOWN && "$r" == UP ]] \
  && resultat A04 OK "Annuaire indisponible : aucune connexion (503, pas de mode dégradé), session ouverte conservée (jeton et renouvellement), sonde DOWN hors readiness [3.3]" \
       "connexion $c3 en $d3 ms ; /auth/me $me ; renouvellement $ren ; sonde $s ; readiness $r" \
  || resultat A04 ECHEC "Annuaire indisponible" "connexion $c3 en $d3 ms ($corps3) ; /auth/me $me ; renouvellement $ren ; sonde $s ; readiness $r"

# A05 : un contrôleur relancé
eval "$GED_V8_LANCER_2"; for i in $(seq 1 30); do connexion "$C" "$(prochaine_ip)"; [[ "$CODE" == 200 ]] && break; sleep 2; done
[[ "$CODE" == 200 ]] && resultat A05 OK "Contrôleur relancé : connexions rétablies sans redémarrer l'application [3.3]" "HTTP $CODE après $i essai(s)" \
  || resultat A05 ECHEC "Contrôleur relancé : connexions rétablies" "HTTP $CODE"
roles_avant=$(grep -o '"roles":\[[^]]*\]' "$T/login.json")

# P04 : désactivation puis réactivation dans l'annuaire (contrôleur relancé seul en service)
uac() {  # uac VALEUR : userAccountControl du compte sur le contrôleur 2
  cat > "$T/Uac.java" <<'JAVA'
import com.unboundid.ldap.sdk.*;
public class Uac { public static void main(String[] a) throws Exception {
  String[] hp = a[0].split(":");
  try (LDAPConnection c = new LDAPConnection(hp[0], Integer.parseInt(hp[1]), "CN=svc-ged-ldap,OU=Services,DC=marchicamed,DC=ma", a[3])) {
    String dn = c.search("DC=marchicamed,DC=ma", SearchScope.SUB, "(sAMAccountName=" + a[1] + ")").getSearchEntries().get(0).getDN();
    c.modify(dn, new Modification(ModificationType.REPLACE, "userAccountControl", a[2])); } } }
JAVA
  java -cp "$GED_V8_CP" "$T/Uac.java" "$GED_V8_LDAP_2" "$C" "$1" "$MDP" 2>/dev/null
}
sleep 61   # limitation par identifiant (DAT 3.4.1) : les essais précédents comptent
uac 514; connexion "$C" "$(prochaine_ip)"; cd=$CODE; corpsd="$(head -c 200 "$T/login.json")"
uac 512; connexion "$C" "$(prochaine_ip)"; cr=$CODE; roles_apres=$(grep -o '"roles":\[[^]]*\]' "$T/login.json")
[[ "$cd" == 401 && "$cr" == 200 && -n "$roles_avant" && "$roles_avant" == "$roles_apres" ]] \
  && resultat P04-02 OK "Compte désactivé dans l'annuaire : connexion refusée ; réactivé : connexion rétablie avec les mêmes rôles [3.4.2, D1, P-04]" "désactivé $cd ; réactivé $cr, $roles_apres" \
  || resultat P04-02 ECHEC "Compte désactivé puis réactivé" "désactivé $cd ($corpsd) ; réactivé $cr ; avant $roles_avant / après $roles_apres"
bilan "P-02 / P-04 annuaire à plusieurs contrôleurs"
