#!/usr/bin/env bash
# Autotest des scripts de recette E1 sur la base de qa (ged_qa_test par défaut).
#
# Un contrôle de recette qui ne sait pas échouer ne prouve rien : on charge un schéma
# conforme (aucun ECHEC attendu) et un schéma volontairement non conforme (chaque
# contrôle doit lever son écart), puis on vérifie le verdict de verifier-socle.sh.
#
# Usage : PGHOST=localhost PGUSER=postgres ./lancer-autotest.sh [base]

source "$(dirname "${BASH_SOURCE[0]}")/../../lib/commun.sh"
ICI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E1="$(cd "$ICI/.." && pwd)"
BASE="${1:-ged_qa_test}"
refuser_production "$BASE"
[[ "$BASE" == ged_qa* ]] || fatal "l'autotest ne s'exécute que sur une base qa (ged_qa*), pas sur « $BASE »"
trouver_psql
export PGHOST="${PGHOST:-localhost}" PGUSER="${PGUSER:-postgres}"

if [[ "$(PGDATABASE=postgres pg -At -c "SELECT 1 FROM pg_database WHERE datname = '$BASE'")" != "1" ]]; then
  info "création de la base $BASE"
  PGDATABASE=postgres pg -c "CREATE DATABASE \"$BASE\" ENCODING 'UTF8' TEMPLATE template0"
fi
export PGDATABASE="$BASE"
pg -f "$ICI/fixture-conforme.sql" || fatal "chargement fixture-conforme.sql"
pg -f "$ICI/fixture-non-conforme.sql" || fatal "chargement fixture-non-conforme.sql"

ROLES=(--owner qa_ged_owner --app qa_ged_app --lecture qa_ged_readonly)
SORTIE_OK="$(bash "$E1/verifier-socle.sh" --schema recette_ok "${ROLES[@]}" --base-vierge 2>&1)"; CODE_OK=$?
SORTIE_KO="$(bash "$E1/verifier-socle.sh" --schema recette_ko "${ROLES[@]}" --base-vierge 2>&1)"; CODE_KO=$?
SORTIE_SU="$(bash "$E1/verifier-socle.sh" --schema recette_ok --owner postgres --app qa_ged_app --lecture qa_role_inexistant --sans-sondes 2>&1)"; CODE_SU=$?

statut_de() { grep "^RESULTAT|$2|" <<< "$1" | head -1 | cut -d'|' -f3; }

# 1. Schéma conforme : aucun ECHEC, code 0 ; les exceptions du journal d'audit s'appliquent.
if [[ $CODE_OK -eq 0 ]] && ! grep -q '|ECHEC|' <<< "$SORTIE_OK"; then
  resultat AT-01 OK "Schéma conforme : aucun écart signalé" "$(grep '^BILAN' <<< "$SORTIE_OK")"
else
  resultat AT-01 ECHEC "Schéma conforme : aucun écart signalé" "code $CODE_OK ; $(grep '|ECHEC|' <<< "$SORTIE_OK" | cut -d'|' -f2,5 | tr '\n' ' ')"
fi
if grep -q 'exception justifiée E1-C04 sur journal_audit' <<< "$SORTIE_OK"; then
  resultat AT-02 OK "Exceptions justifiées appliquées (journal_audit)"
else
  resultat AT-02 ECHEC "Exceptions justifiées appliquées (journal_audit)"
fi

# 2. Schéma non conforme : chaque contrôle lève son écart.
[[ $CODE_KO -eq 1 ]] && resultat AT-03 OK "Schéma non conforme : code de sortie 1" \
                     || resultat AT-03 ECHEC "Schéma non conforme : code de sortie 1" "code $CODE_KO"
ATTENDUS_KO="E1-C01:ECHEC E1-C02:ECHEC E1-C03:ECHEC E1-C04:ECHEC E1-C05:ECHEC E1-C06:ECHEC E1-C07:ECHEC
E1-C08:ECHEC E1-C09:ECHEC E1-C10:ECHEC E1-C11:ECHEC E1-C12:AVERT E1-C13:AVERT E1-C14:AVERT E1-C15:ECHEC
E1-C16:ECHEC E1-C17:ECHEC E1-C18:ECHEC E1-C19:ECHEC E1-C20:ECHEC E1-C21:ECHEC E1-C22:ECHEC E1-C23:ECHEC
E1-C32:ECHEC E1-C33:ECHEC E1-C34:ECHEC E1-C35:ECHEC E1-C36:ECHEC E1-C38:ECHEC E1-C39:ECHEC E1-C40:ECHEC
E1-C41:AVERT E1-P01:ECHEC E1-P09:ECHEC E1-P21:ECHEC E1-P22:ECHEC"
manques=()
for paire in $ATTENDUS_KO; do
  id="${paire%%:*}"; attendu="${paire##*:}"; obtenu="$(statut_de "$SORTIE_KO" "$id")"
  [[ "$obtenu" == "$attendu" ]] || manques+=("$id attendu $attendu obtenu ${obtenu:-absent}")
done
if [[ ${#manques[@]} -eq 0 ]]; then
  resultat AT-04 OK "Schéma non conforme : les $(wc -w <<< "$ATTENDUS_KO") écarts volontaires sont détectés"
else
  resultat AT-04 ECHEC "Schéma non conforme : écarts volontaires détectés" "$(printf '%s ; ' "${manques[@]}")"
fi

# 3. Rôle superutilisateur et rôle absent : détectés sans faire planter le script.
[[ "$(statut_de "$SORTIE_SU" E1-C30)" == ECHEC && $CODE_SU -eq 1 ]] \
  && resultat AT-05 OK "Rôle absent détecté (E1-C30) sans arrêt du script" \
  || resultat AT-05 ECHEC "Rôle absent détecté (E1-C30)" "code $CODE_SU ; $(grep -E 'ERREUR_EXECUTION|E1-C30' <<< "$SORTIE_SU" | head -2)"

# Rôle superutilisateur : postgres existe, les deux autres aussi.
SORTIE_SU2="$(bash "$E1/verifier-socle.sh" --schema recette_ok --owner postgres --app qa_ged_app --lecture qa_ged_readonly --sans-sondes 2>&1)"
[[ "$(statut_de "$SORTIE_SU2" E1-C31)" == ECHEC ]] \
  && resultat AT-06 OK "Rôle superutilisateur détecté (E1-C31)" \
  || resultat AT-06 ECHEC "Rôle superutilisateur détecté (E1-C31)" "$(statut_de "$SORTIE_SU2" E1-C31)"

# 4. Analyse statique des changelogs (sans base) : conforme puis non conforme.
trouver_python
SORTIE_A_OK="$("$PYTHON" "$E1/analyser-changelogs.py" --backend "$ICI/changelogs/conforme" 2>&1)"; CODE_A_OK=$?
SORTIE_A_KO="$("$PYTHON" "$E1/analyser-changelogs.py" --backend "$ICI/changelogs/non-conforme" 2>&1)"; CODE_A_KO=$?
SORTIE_A_OK="${SORTIE_A_OK//$'\r'/}"; SORTIE_A_KO="${SORTIE_A_KO//$'\r'/}"
[[ $CODE_A_OK -eq 0 ]] && ! grep -q '|ECHEC|' <<< "$SORTIE_A_OK" \
  && resultat AT-07 OK "Changelogs conformes : aucun écart signalé" \
  || resultat AT-07 ECHEC "Changelogs conformes : aucun écart signalé" "$(grep '|ECHEC|' <<< "$SORTIE_A_OK" | cut -d'|' -f2,5 | head -3)"
ATTENDUS_A="E1-A02:ECHEC E1-A03:ECHEC E1-A05:ECHEC E1-A06:AVERT E1-A07:ECHEC E1-A08:ECHEC E1-A09:ECHEC E1-A10:ECHEC
E1-A11:ECHEC E1-A12:AVERT E1-A20:ECHEC E1-A21:ECHEC E1-A22:ECHEC E1-A23:AVERT E1-A24:ECHEC E1-A25:ECHEC"
manques=()
for paire in $ATTENDUS_A; do
  id="${paire%%:*}"; attendu="${paire##*:}"; obtenu="$(statut_de "$SORTIE_A_KO" "$id")"
  [[ "$obtenu" == "$attendu" ]] || manques+=("$id attendu $attendu obtenu ${obtenu:-absent}")
done
[[ $CODE_A_KO -eq 1 && ${#manques[@]} -eq 0 ]] \
  && resultat AT-08 OK "Changelogs non conformes : les $(wc -w <<< "$ATTENDUS_A") écarts volontaires sont détectés" \
  || resultat AT-08 ECHEC "Changelogs non conformes : écarts détectés" "code $CODE_A_KO ; $(printf '%s ; ' "${manques[@]}")"

if [[ "${VERBEUX:-0}" == 1 ]]; then
  printf '\n----- schéma conforme -----\n%s\n----- schéma non conforme -----\n%s\n' "$SORTIE_OK" "$SORTIE_KO"
fi
bilan "autotest E1 sur $BASE"
