#!/usr/bin/env bash
# Recette T-088 — modules métier activables (DAT V3 §9.3 : « déploiement par processus métier »).
#
# À lancer contre une instance démarrée avec, au minimum :
#   GED_MODULES_WORKFLOW_ACTIF=false GED_MODULES_EXPORT_ACTIF=false
#   GED_MODULES_INTEGRATION_ACTIF=false GED_MODULES_OCR_ACTIF=false
# (notifications et cycle de vie restent actifs). Contrôles :
#   M01 GET /api/v1/modules : six modules, état conforme à la configuration ;
#   M02 routes d'un module inactif : 404 MODULE_INACTIF, avec ou sans authentification ;
#       module integration : toute requête X-API-Key refusée ;
#   M03 socle intact : liste, dépôt, fiche, téléchargement ;
#   M04 OCR inactif : aucun job OCR créé au dépôt (traitements de fond arrêtés) ;
#   M05 workflow inactif : un dépôt sous une règle de workflow n'ouvre aucun circuit
#       bloquant (sinon le document resterait inutilisable sans moyen de le valider) ;
#   M06 métrique ged_module_actif publiée par module.
# Variables : GED_URL, GED_URL_SANTE, GED_RECETTE_MOT_DE_PASSE, PGHOST, GED_V8_BASE (base de l'instance).
# Usage : verifier-modules.sh

source "$(dirname "${BASH_SOURCE[0]}")/../lib/commun.sh"
source "$(dirname "${BASH_SOURCE[0]}")/../lib/api.sh"
trouver_psql
BASE="${GED_V8_BASE:?GED_V8_BASE (base de l'instance) obligatoire}"
refuser_production "$BASE"
pgq() { PGUSER=postgres PGDATABASE="$BASE" "$PSQL" -X -q -At -c "SET search_path = ged" -c "$1" | tr -d '\r'; }
export GED_CHAMP_IDENTIFIANT=identifiant GED_RECETTE_IDENTIFIANT="${GED_E3_ADMIN:-sbennani}"
api_connexion || fatal "connexion de $GED_RECETTE_IDENTIFIANT"

# M01
api_appel GET /api/v1/modules
etat="$(tr -d '\r\n' < "$HTTP_CORPS" | grep -o '"code":"[a-z]*"[^}]*"actif":[a-z]*' | sed 's/"code":"\([a-z]*\)".*"actif":\([a-z]*\)/\1=\2/' | sort | tr '\n' ' ')"
attendu="cycledevie=true export=false integration=false notifications=true ocr=false workflow=false "
[[ "$HTTP_CODE" == 200 && "$etat" == "$attendu" ]] \
  && resultat T088-M01 OK "État des modules publié (GET /api/v1/modules), conforme à la configuration [9.3, T-088]" "$etat" \
  || resultat T088-M01 ECHEC "État des modules publié (GET /api/v1/modules)" "HTTP $HTTP_CODE, $etat (attendu $attendu)"

# M02
lignes=(); ok=1
for r in "GET /api/v1/workflow/regles" "GET /api/v1/workflow/a-traiter" "POST /api/v1/exports/dossiers/$(uuid_aleatoire)" \
         "GET /api/v1/applications" "GET /api/v1/ocr/etat" "GET /api/v1/recherche/plein-texte?q=convention"; do
  m="${r%% *}"; c="${r#* }"
  api_appel "$m" "$c" -H "Idempotency-Key: $(uuid_aleatoire)"; a="$HTTP_CODE/$(code_metier)"
  s="$(curl -s -o "$API_TMP/anon" -w '%{http_code}' -X "$m" "${GED_URL%/}$c")"; sc="$(grep -o '"code":"[A-Z_]*"' "$API_TMP/anon" | cut -d'"' -f4)"
  lignes+=("$c : $a, anonyme $s/$sc")
  [[ "$a" == 404/MODULE_INACTIF && "$s" == 404 && "$sc" == MODULE_INACTIF ]] || ok=0
done
s="$(curl -s -o "$API_TMP/cle" -w '%{http_code}' -H 'X-API-Key: ged_dev_00000000_inconnu' "${GED_URL%/}/api/v1/documents")"; sc="$(grep -o '"code":"[A-Z_]*"' "$API_TMP/cle" | cut -d'"' -f4)"
lignes+=("X-API-Key sur /api/v1/documents : $s/$sc"); [[ "$s" == 404 && "$sc" == MODULE_INACTIF ]] || ok=0
[[ $ok == 1 ]] && resultat T088-M02 OK "Routes des modules inactifs : 404 MODULE_INACTIF avant l'authentification ; module integration : toute requête X-API-Key refusée [9.3, T-088]" "$(IFS=';'; echo "${lignes[*]}")" \
               || resultat T088-M02 ECHEC "Routes des modules inactifs fermées" "$(IFS=';'; echo "${lignes[*]}")"

# M03, M04, M05 : dépôt sous la règle de démonstration de Comptabilité (TD-FACT)
T="$(pgq "SELECT id FROM type_document WHERE code = 'TD-FACT'")"
REGLE="$(pgq "SELECT coalesce(t.regle_workflow_id, n.regle_workflow_id) FROM type_document t JOIN noeud n ON n.id = t.workspace_id WHERE t.id = '$T'")"
api_depot "$DONNEES/scan_fr_courrier.pdf" "qav8-modules-$RANDOM.pdf" "$T"; D="$DEPOT_ID"; CODE_DEPOT="$HTTP_CODE"
api_appel GET "/api/v1/documents?size=5"; CODE_LISTE="$HTTP_CODE"
api_appel GET "/api/v1/documents/$D"; CODE_FICHE="$HTTP_CODE"; ACTIF="$(json_champ active)"; OCR="$(json_champ statutOcr)"
api_telecharger "$D" "$API_TMP/dl"; CODE_DL="$HTTP_CODE"
[[ "$CODE_DEPOT" =~ ^20[12]$ && "$CODE_LISTE" == 200 && "$CODE_FICHE" == 200 && "$CODE_DL" == 200 ]] \
  && resultat T088-M03 OK "Socle toujours actif : dépôt, liste, fiche, téléchargement [9.3]" "dépôt $CODE_DEPOT, liste $CODE_LISTE, fiche $CODE_FICHE, téléchargement $CODE_DL" \
  || resultat T088-M03 ECHEC "Socle toujours actif" "dépôt $CODE_DEPOT, liste $CODE_LISTE, fiche $CODE_FICHE, téléchargement $CODE_DL"
JOBS="$(pgq "SELECT count(*) FROM ocr_job WHERE document_id = '$D'")"
[[ "$JOBS" == 0 ]] \
  && resultat T088-M04 OK "OCR inactif : aucun job OCR créé au dépôt [9.3, 4.3.4]" "dépôt $CODE_DEPOT, statutOcr « $OCR », jobs 0" \
  || resultat T088-M04 ECHEC "OCR inactif : aucun job OCR au dépôt" "dépôt $CODE_DEPOT, statutOcr « $OCR », $JOBS job(s) créé(s) alors que le module est inactif"
CIRCUITS="$(pgq "SELECT count(*) FROM circuit WHERE document_id = '$D'")"
if [[ -z "$REGLE" ]]; then
  resultat T088-M05 NA "Workflow inactif et dépôt sous règle" "aucune règle sur TD-FACT / Comptabilité"
elif [[ "$CIRCUITS" == 0 && "$ACTIF" == true ]]; then
  resultat T088-M05 OK "Workflow inactif : dépôt sous règle sans circuit, document utilisable [9.3, 12.8]" "circuits 0, active true"
else
  resultat T088-M05 ECHEC "Workflow inactif : un dépôt sous règle ouvre un circuit que plus aucune route ne permet de traiter [9.3, 12.8]" \
    "règle $REGLE, circuits $CIRCUITS, document active=$ACTIF, routes /api/v1/workflow/** en 404 MODULE_INACTIF"
fi

# M06
met="$(curl -s "${GED_URL_SANTE%/}/actuator/prometheus" | grep '^ged_module_actif' | sed 's/{[^}]*module="\([a-z]*\)"[^}]*}/ \1/' | awk '{print $2"="$3}' | sort | tr '\n' ' ')"
[[ "$met" == *"workflow=0.0"* && "$met" == *"notifications=1.0"* ]] \
  && resultat T088-M06 OK "Métrique ged_module_actif par module [9.3, 6.7]" "$met" \
  || resultat T088-M06 ECHEC "Métrique ged_module_actif par module" "« $met »"
bilan "T-088 modules"
