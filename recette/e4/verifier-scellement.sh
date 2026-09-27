#!/usr/bin/env bash
# Recette E4 — critère de sortie « une modification manuelle du journal fait échouer la
# vérification du scellement » (DAT V3 §7.4.2).
#
#   S01 vérification à la demande (POST /api/v1/audit/verifications) : chaîne intègre, au moins
#       une période scellée ;
#   S02 ged_owner désactive les déclencheurs et modifie une ligne SCELLÉE → la vérification
#       signale l'empreinte différente de la période ;
#   S03 ligne restaurée, déclencheurs réactivés → la vérification redevient intègre ;
#   S04 ged_owner réécrit l'empreinte d'un scellement en base → écart avec la copie hors base
#       détecté ;
#   S05 la vérification est elle-même tracée (AUDIT_VERIFIE).
# À exécuter sur une base de RECETTE uniquement (la ligne est restaurée à l'identique).
#
# Usage : GED_URL=… GED_RECETTE_MOT_DE_PASSE=… verifier-scellement.sh --base NOM [--admin COMPTE]

source "$(dirname "${BASH_SOURCE[0]}")/../lib/commun.sh"
BASE=""; SCHEMA=ged; ADMIN="${GED_E4_ADMIN:-sbennani}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) BASE="$2"; shift 2 ;;
    --admin) ADMIN="$2"; shift 2 ;;
    *) fatal "option inconnue : $1" ;;
  esac
done
[[ -n "$BASE" && -n "${GED_URL:-}" && -n "${GED_RECETTE_MOT_DE_PASSE:-}" ]] || fatal "--base, GED_URL et GED_RECETTE_MOT_DE_PASSE obligatoires"
refuser_production "$BASE"
trouver_psql
export PGHOST="${PGHOST:-localhost}"
T="$(mktemp -d "${TMPDIR:-/tmp}/qa-e4.XXXXXX")"; trap 'rm -rf "$T"' EXIT
owner() { PGUSER=ged_owner PGDATABASE="$BASE" "$PSQL" -X -q -At -v ON_ERROR_STOP=1 -c "SET search_path = $SCHEMA" -c "$1" | tr -d '\r'; }

for _ in 1 2 3; do
  code="$(curl -s -o "$T/l" -w '%{http_code}' -X POST "$GED_URL/api/v1/auth/login" -H 'Content-Type: application/json' \
    --data-binary "{\"identifiant\":\"$ADMIN\",\"motDePasse\":\"$(printf '%s' "$GED_RECETTE_MOT_DE_PASSE" | sed -e 's/[\\]/&&/g' -e 's/"/\\"/g')\"}")"
  [[ "$code" == 429 ]] && { info "limitation de débit : attente 61 s"; sleep 61; continue; }; break
done
JETON="$(grep -o '"token":"[^"]*"' "$T/l" | cut -d'"' -f4)"; : > "$T/l"
[[ -n "$JETON" ]] || fatal "connexion de $ADMIN impossible (HTTP $code)"
verifier() { curl -s -X POST "$GED_URL/api/v1/audit/verifications" -H "Authorization: Bearer $JETON" -o "$T/v" -w '%{http_code}'; tr -d '\r\n' < "$T/v"; }

r="$(verifier)"; c="${r:0:3}"; corps="${r:3}"
n="$(grep -o '"periodesVerifiees":[0-9]*' <<< "$corps" | cut -d: -f2)"
if [[ "$c" != 200 || "${n:-0}" == 0 ]]; then
  resultat E4-S01 NA "Vérification du scellement : chaîne intègre" "HTTP $c, aucune période scellée (scellement horaire à hh:05 des périodes closes) : relancer plus tard"
  bilan "E4 scellement"; exit 0
fi
grep -q '"anomalies":\[\]' <<< "$corps" && resultat E4-S01 OK "Vérification à la demande : chaîne intègre [7.4.2]" "$n période(s), $(grep -o '"enregistrementsVerifies":[0-9]*' <<< "$corps" | cut -d: -f2) enregistrement(s)" \
                                        || resultat E4-S01 ECHEC "Vérification à la demande : chaîne intègre [7.4.2]" "${corps:0:300}"

# Une ligne effectivement couverte par un scellement.
ligne="$(owner "SELECT j.id || '|' || j.action || '|' || j.horodatage FROM journal_audit j JOIN journal_audit_scellement s ON j.id BETWEEN s.premier_numero AND s.dernier_numero ORDER BY j.id DESC LIMIT 1")"
ID="${ligne%%|*}"; reste="${ligne#*|}"; ACTION="${reste%%|*}"
[[ -n "$ID" ]] || fatal "aucune ligne scellée trouvée"
info "altération de la ligne $ID (action $ACTION) par ged_owner, déclencheurs désactivés"
tables="$(owner "SELECT string_agg(c.relname, ' ') FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='$SCHEMA' AND c.relname LIKE 'journal_audit%' AND c.relkind IN ('r','p') AND c.relname <> 'journal_audit_scellement'")"
desactiver() { for t in $tables; do owner "ALTER TABLE $t $1 TRIGGER USER" >/dev/null; done; }
desactiver DISABLE
owner "UPDATE journal_audit SET action = 'QA_ALTERATION' WHERE id = $ID" >/dev/null && altere=1 || altere=0
desactiver ENABLE
r="$(verifier)"; corps="${r:3}"
[[ $altere == 1 ]] && grep -q 'EMPREINTE_DIFFERENTE' <<< "$corps" \
  && resultat E4-S02 OK "Ligne scellée modifiée par ged_owner (déclencheurs désactivés) : vérification en échec, EMPREINTE_DIFFERENTE [7.4.2, critère E4]" "$(grep -o '"anomalies":\[[^]]*\]' <<< "$corps" | cut -c1-250)" \
  || resultat E4-S02 ECHEC "Altération détectée par la vérification du scellement [7.4.2, critère E4]" "modification appliquée $altere ; ${corps:0:300}"

desactiver DISABLE
owner "UPDATE journal_audit SET action = '$ACTION' WHERE id = $ID" >/dev/null
desactiver ENABLE
r="$(verifier)"; corps="${r:3}"
grep -q '"anomalies":\[\]' <<< "$corps" && resultat E4-S03 OK "Ligne restaurée : chaîne de nouveau intègre (le scellement porte sur le contenu)" \
                                        || resultat E4-S03 ECHEC "Ligne restaurée : chaîne de nouveau intègre" "${corps:0:300}"

# Scellement réécrit en base : la copie hors base le contredit.
sc="$(owner "SELECT periode_debut || '|' || empreinte FROM journal_audit_scellement ORDER BY periode_debut DESC LIMIT 1")"
DEBUT="${sc%%|*}"; EMPR="${sc#*|}"
owner "ALTER TABLE journal_audit_scellement DISABLE TRIGGER USER" >/dev/null
owner "UPDATE journal_audit_scellement SET empreinte = repeat('0', length(empreinte)) WHERE periode_debut = '$DEBUT'" >/dev/null
r="$(verifier)"; corps="${r:3}"
owner "UPDATE journal_audit_scellement SET empreinte = '$EMPR' WHERE periode_debut = '$DEBUT'" >/dev/null
owner "ALTER TABLE journal_audit_scellement ENABLE TRIGGER USER" >/dev/null
grep -qE 'EXPORT_DIFFERENT|EMPREINTE' <<< "$corps" && grep -qv '"anomalies":\[\]' <<< "$corps" \
  && resultat E4-S04 OK "Scellement réécrit en base : écart avec la copie hors base détecté [7.4.2]" "$(grep -o '"anomalies":\[[^]]*\]' <<< "$corps" | cut -c1-200)" \
  || resultat E4-S04 ECHEC "Scellement réécrit en base : écart détecté [7.4.2]" "${corps:0:300}"
r="$(verifier)"; grep -q '"anomalies":\[\]' <<< "${r:3}" || info "ATTENTION : chaîne non intègre après restauration : ${r:3:300}"

curl -s "$GED_URL/api/v1/audit/evenements?action=AUDIT_VERIFIE&taille=5" -H "Authorization: Bearer $JETON" -o "$T/e"
grep -q '"action":"AUDIT_VERIFIE"' "$T/e" && resultat E4-S05 OK "Chaque vérification est elle-même tracée (AUDIT_VERIFIE) [7.4.2]" \
                                         || resultat E4-S05 ECHEC "Vérification tracée (AUDIT_VERIFIE)" "$(head -c 200 "$T/e")"
bilan "E4 scellement $BASE"
