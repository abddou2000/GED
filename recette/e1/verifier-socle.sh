#!/usr/bin/env bash
# Recette E1 — contrôle du socle de données PostgreSQL d'une base déjà migrée.
#
# Vérifie, par requêtes sur le catalogue (information_schema, pg_class, pg_index,
# pg_constraint, pg_roles…) puis par sondes réelles sous chaque rôle :
# conventions de nommage, clés UUID, suppression douce, JSONB + GIN, journal
# Liquibase, rôles ged_owner / ged_app / ged_readonly et leurs privilèges.
# Lecture seule : aucune donnée ni aucun objet n'est modifié.
#
# Usage :
#   PGHOST=localhost PGUSER=postgres PGDATABASE=ged_uat ./verifier-socle.sh [options]
# Options :
#   --schema NOM            schéma applicatif (défaut : ged)
#   --schema-liquibase NOM  schéma du registre Liquibase (défaut : ged_liquibase s'il existe,
#                           sinon le schéma applicatif)
#   --owner/--app/--lecture noms des rôles (défaut : ged_owner, ged_app, ged_readonly)
#   --table-document NOM    table des documents (défaut : document)
#   --base-vierge           la base vient d'être créée par Liquibase seul : les
#                           référentiels métier doivent être vides (E1-C23)
#   --exceptions FICHIER    écarts justifiés (défaut : exceptions.txt à côté du script)
#   --sans-sondes           ne pas exécuter les sondes dynamiques (rôle non superutilisateur)
# Sortie : lignes RESULTAT|id|OK/ECHEC/AVERT/NA|libellé|détail, puis BILAN. Code 0 si aucun ECHEC.

source "$(dirname "${BASH_SOURCE[0]}")/../lib/commun.sh"
ICI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SCHEMA=ged; SCHEMA_LB=""; R_OWNER=ged_owner; R_APP=ged_app; R_RO=ged_readonly; T_DOC=document
BASE_VIERGE=0; SONDES=1; EXCEPTIONS="$ICI/exceptions.txt"
REGEX_AUDIT='^journal_audit'
# Référentiels métier créés exclusivement depuis l'interface (DAT §4.2.1, principe P1).
REGEX_REF='^(type_document|index_def|plan_indexation|plan_index|noeud|document|regle_workflow|regle_validateur|groupe_ged|groupe_membre|habilitation|application|cle_api|cle_api_portee|utilisateur)$'

while [[ $# -gt 0 ]]; do
  case "$1" in
    --schema) SCHEMA="$2"; shift 2 ;;
    --schema-liquibase) SCHEMA_LB="$2"; shift 2 ;;
    --owner) R_OWNER="$2"; shift 2 ;;
    --app) R_APP="$2"; shift 2 ;;
    --lecture) R_RO="$2"; shift 2 ;;
    --table-document) T_DOC="$2"; shift 2 ;;
    --base-vierge) BASE_VIERGE=1; shift ;;
    --exceptions) EXCEPTIONS="$2"; shift 2 ;;
    --sans-sondes) SONDES=0; shift ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) fatal "option inconnue : $1" ;;
  esac
done

trouver_psql
if [[ -z "$SCHEMA_LB" ]]; then
  # Registre dans le schéma applicatif s'il y est, sinon dans ged_liquibase (choix de dev1).
  SCHEMA_LB="$(pg -At -c "SELECT CASE WHEN to_regclass(quote_ident('$SCHEMA') || '.databasechangelog') IS NOT NULL THEN '$SCHEMA'
                                      WHEN EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'ged_liquibase') THEN 'ged_liquibase'
                                      ELSE '$SCHEMA' END")"
  SCHEMA_LB="${SCHEMA_LB//$'\r'/}"
fi
info "Base : ${PGDATABASE:-?} sur ${PGHOST:-local}:${PGPORT:-5432} (utilisateur ${PGUSER:-?}), schéma $SCHEMA (registre Liquibase : $SCHEMA_LB)"
info "Rôles : $R_OWNER / $R_APP / $R_RO — base vierge : $BASE_VIERGE"

VARS=(-v "schema=$SCHEMA" -v "schema_lb=$SCHEMA_LB" -v "r_owner=$R_OWNER" -v "r_app=$R_APP" -v "r_ro=$R_RO" -v "t_document=$T_DOC"
      -v "regex_audit=$REGEX_AUDIT" -v "regex_referentiels=$REGEX_REF" -v "base_vierge=$BASE_VIERGE")

CONSTATS="$(pg -At -F'|' "${VARS[@]}" -f "$ICI/controles-socle.sql")" || fatal "échec d'exécution de controles-socle.sql"
CONSTATS="${CONSTATS//$'\r'/}"   # psql sous Windows termine ses lignes par CRLF

# Écarts justifiés : lignes « controle|motif d'objet (regex)|justification ».
declare -a EXC_CTRL=() EXC_OBJ=() EXC_JUST=()
if [[ -f "$EXCEPTIONS" ]]; then
  while IFS='|' read -r c o j; do
    [[ -z "$c" || "$c" =~ ^# ]] && continue
    EXC_CTRL+=("$c"); EXC_OBJ+=("$o"); EXC_JUST+=("$j")
  done < "$EXCEPTIONS"
fi

est_exception() {  # $1 contrôle, $2 objet → 0 et affiche la justification si couvert
  local i
  for i in "${!EXC_CTRL[@]}"; do
    if [[ "${EXC_CTRL[$i]}" == "$1" && "$2" =~ ${EXC_OBJ[$i]} ]]; then
      info "exception justifiée $1 sur $2 : ${EXC_JUST[$i]}"; return 0
    fi
  done
  return 1
}

# Catalogue : identifiant|libellé (référence DAT V3 / matrice)
CATALOGUE="$(cat <<'FIN'
E1-C24|Base encodée en UTF-8 [5.3.2]
E1-C26|PostgreSQL 16 ou plus [2.2]
E1-C25|Configurations french et arabic, extensions unaccent et pg_trgm [2.2, 4.4]
E1-C01|Tables et vues en snake_case [4.2.2]
E1-C02|Colonnes en snake_case [4.2.2]
E1-C07|Colonnes de clé étrangère suffixées _id [4.2.2]
E1-C08|Contraintes de clé étrangère préfixées fk_ [4.2.2]
E1-C09|Contraintes d'unicité préfixées uk_ [4.2.2]
E1-C10|Contraintes de vérification préfixées ck_ [4.2.2]
E1-C11|Index préfixés idx_ (uk_ pour un index unique autonome) [4.2.2]
E1-C12|Index de la forme idx_<table>_<colonnes> [4.2.2]
E1-C13|Clés étrangères indexées [4.2.2]
E1-C03|Toute table a une clé primaire [12.1]
E1-C04|Clé primaire = colonne unique id [4.2.2, 12.1]
E1-C05|Clés primaires de type uuid [12.1, 5.3.2]
E1-C06|Aucune colonne auto-incrémentée [12.1]
E1-C14|Horodatages avec fuseau (timestamptz) [5.3.2]
E1-C15|Suppression douce complète : supprime, supprime_par (uuid), supprime_le (timestamptz) [12.5]
E1-C16|Table document avec suppression douce [12.5]
E1-C17|document.metadonnees de type jsonb [12.7]
E1-C18|Index GIN sur document.metadonnees [12.7]
E1-C19|Schéma géré par Liquibase, sans Flyway [2.2, 4.2.1]
E1-C20|Changesets nommés AAAAMMJJHHmm_objet.xml [4.2.2]
E1-C21|Tous les changesets exécutés [4.2.1]
E1-C22|Amorçage initial en changesets data-initial [4.2.1]
E1-C23|Référentiels métier vides sur base vierge [4.2.1]
E1-C30|Rôles ged_owner, ged_app, ged_readonly présents [4.2.3]
E1-C31|Aucun rôle superutilisateur ni administrateur [4.2.3]
E1-C32|ged_owner propriétaire du schéma et de tous les objets [4.2.3]
E1-C33|ged_app sans aucun droit DDL [4.2.3]
E1-C34|ged_app avec SELECT, INSERT, UPDATE, DELETE sur les tables applicatives [4.2.3]
E1-C35|ged_app sans droit hors DML [4.2.3]
E1-C36|Tables d'audit : ged_app en INSERT et SELECT seulement [4.2.3, 7.4.2]
E1-C37|ged_app n'écrit pas dans le journal Liquibase [4.2.1]
E1-C38|ged_readonly en SELECT seulement [4.2.3]
E1-C39|ged_readonly lit toutes les tables [4.2.3]
E1-C40|Aucun droit accordé à PUBLIC [4.2.3, 6.2.3 A05]
E1-C41|Privilèges par défaut pour les tables futures [4.2.3]
FIN
)"

while IFS='|' read -r id libelle; do
  [[ "$id" == "E1-C23" && "$BASE_VIERGE" -eq 0 ]] && { resultat "$id" NA "$libelle" "contrôle réservé à --base-vierge"; continue; }
  nb_err=0; nb_av=0; nb_na=0; exemples=(); na_detail=""
  while IFS='|' read -r c g objet detail; do
    [[ "$c" != "$id" ]] && continue
    est_exception "$c" "$objet" && continue
    case "$g" in
      ERREUR) nb_err=$((nb_err + 1)); exemples+=("$objet : $detail") ;;
      AVERT) nb_av=$((nb_av + 1)); exemples+=("$objet : $detail") ;;
      NA) nb_na=$((nb_na + 1)); na_detail="$detail" ;;
    esac
  done <<< "$CONSTATS"
  resume="$(printf '%s ; ' "${exemples[@]:0:5}")"; [[ ${#exemples[@]} -gt 5 ]] && resume+="… (${#exemples[@]} au total)"
  if (( nb_err > 0 )); then resultat "$id" ECHEC "$libelle" "$nb_err écart(s) : $resume"
  elif (( nb_av > 0 )); then resultat "$id" AVERT "$libelle" "$nb_av point(s) : $resume"
  elif (( nb_na > 0 )); then resultat "$id" NA "$libelle" "$na_detail"
  else resultat "$id" OK "$libelle"; fi
done <<< "$CATALOGUE"

if [[ "$SONDES" -eq 1 ]]; then
  SONDES_OUT="$(pg -At -F'|' "${VARS[@]}" -f "$ICI/sondes-privileges.sql" 2>&1)" || {
    resultat "E1-P00" ECHEC "Sondes de privilèges exécutables" "$(echo "$SONDES_OUT" | tail -1)"; SONDES_OUT=""; }
  SONDES_OUT="${SONDES_OUT//$'\r'/}"
  while IFS='|' read -r id role op attendu obtenu; do
    [[ -z "$id" ]] && continue
    lib="Sonde : $role — $op → $attendu attendu [4.2.3]"
    if [[ "$attendu" == "NA" ]]; then resultat "$id" NA "Sonde : $role — $op" "$obtenu"
    elif [[ "$attendu" == "$obtenu" ]]; then resultat "$id" OK "$lib"
    else resultat "$id" ECHEC "$lib" "obtenu : $obtenu"; fi
  done <<< "$SONDES_OUT"
fi

bilan "E1 socle ${PGDATABASE:-}/$SCHEMA"
