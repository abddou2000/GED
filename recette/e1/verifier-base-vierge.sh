#!/usr/bin/env bash
# Recette E1 — « Base vierge créée uniquement par Liquibase » (critère de sortie E1).
#
# Déroulé, sur une base JETABLE (jamais la base de l'environnement) :
#   1. suppression puis préparation de la base par backend/scripts/db/preparer-base.sql
#      (base, schémas ged et ged_liquibase, droits ; les rôles doivent exister) ;
#   2. application du changelog complet par Liquibase avec le compte ged_owner ;
#   3. contrôle du résultat : verifier-socle.sh --base-vierge + AnalyseurChangelogs.java ;
#   4. (option --demarrer-application) démarrage de l'application sur cette base avec
#      le compte ged_app : Hibernate doit valider le schéma sans le modifier — le DDL
#      avant et après démarrage doit être IDENTIQUE, preuve qu'aucune table n'est créée
#      hors Liquibase (§4.2.1).
#
# Usage : PGHOST=localhost PGUSER=postgres ./verifier-base-vierge.sh [options]
#   --base NOM                base jetable (défaut : ged_qa_recette_e1 ; doit contenir « recette » ou commencer par ged_qa)
#   --backend CHEMIN          dossier backend (défaut : backend du dépôt)
#   --demarrer-application    étape 4 (construit le JAR si --jar absent)
#   --jar CHEMIN              JAR de l'application à démarrer
#   --port N                  port de démarrage (défaut : 18084, port réservé à qa)
# Sortie : RESULTAT|…, BILAN ; code 0 si aucun ECHEC.

source "$(dirname "${BASH_SOURCE[0]}")/../lib/commun.sh"
source "$RECETTE_RACINE/lib/liquibase.sh"
ICI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

BASE=ged_qa_recette_e1; BACKEND="$DEPOT_RACINE/backend"; DEMARRER=0; JAR=""; PORT=18084
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) BASE="$2"; shift 2 ;;
    --backend) BACKEND="$(cd "$2" && pwd)"; shift 2 ;;
    --demarrer-application) DEMARRER=1; shift ;;
    --jar) JAR="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
    *) fatal "option inconnue : $1" ;;
  esac
done
refuser_production "$BASE"
[[ "$BASE" == ged_qa* || "$BASE" == *recette* ]] || fatal "la base « $BASE » n'est pas une base jetable de recette"
trouver_psql
export PGHOST="${PGHOST:-localhost}" PGPORT="${PGPORT:-5432}" PGUSER="${PGUSER:-postgres}"
TRAVAIL="$(mktemp -d "${TMPDIR:-/tmp}/qa-e1-vierge.XXXXXX")"
LB_URL="jdbc:postgresql://${PGHOST}:${PGPORT}/${BASE}"

# 0. Rôles : créés une fois par serveur par creer-roles.sql (dev1) ; on ne les crée pas ici.
n_roles="$(PGDATABASE=postgres pg -At -c "SELECT count(*) FROM pg_roles WHERE rolname IN ('ged_owner','ged_app','ged_readonly')" | tr -d '\r')"
[[ "$n_roles" == 3 ]] || fatal "rôles ged_owner/ged_app/ged_readonly absents : exécuter backend/scripts/db/creer-roles.sql"

# 1. Base neuve.
info "suppression et préparation de la base $BASE"
PGDATABASE=postgres pg -c "DROP DATABASE IF EXISTS \"$BASE\" WITH (FORCE)" >/dev/null
if PGDATABASE=postgres pg -v base="$BASE" -f "$BACKEND/scripts/db/preparer-base.sql" > "$TRAVAIL/preparer.log" 2>&1; then
  resultat E1-V01 OK "Base préparée par preparer-base.sql (base, schémas, droits)"
else
  resultat E1-V01 ECHEC "Base préparée par preparer-base.sql" "$(tail -2 "$TRAVAIL/preparer.log" | tr '\n' ' ')"; bilan "E1 base vierge"; exit 1
fi
n_tables_avant="$(PGDATABASE="$BASE" pg -At -c "SELECT count(*) FROM pg_tables WHERE schemaname IN ('ged','ged_liquibase')" | tr -d '\r')"
[[ "$n_tables_avant" == 0 ]] && resultat E1-V02 OK "Base vide avant Liquibase" \
                             || resultat E1-V02 ECHEC "Base vide avant Liquibase" "$n_tables_avant table(s) déjà présentes"

# 2. Liquibase seul.
info "liquibase update (compte $LB_USER) sur $LB_URL"
debut=$(date +%s)
if liquibase_executer update > "$TRAVAIL/update.log" 2>&1; then
  resultat E1-V03 OK "Changelog complet appliqué par Liquibase avec $LB_USER" "$(( $(date +%s) - debut )) s"
else
  resultat E1-V03 ECHEC "Changelog complet appliqué par Liquibase avec $LB_USER" "$(grep -E 'ERROR|Exception|Caused' "$TRAVAIL/update.log" | head -3 | tr '\n' ' ')"
  bilan "E1 base vierge"; exit 1
fi
schema_normalise "$BASE" "$TRAVAIL/schema-liquibase.sql" ged ged_liquibase
info "schéma produit : $(grep -c '^CREATE TABLE' "$TRAVAIL/schema-liquibase.sql") table(s), empreinte $(sha256sum "$TRAVAIL/schema-liquibase.sql" | cut -c1-16)"

# 3. Contrôles du résultat.
PGDATABASE="$BASE" bash "$ICI/verifier-socle.sh" --schema ged --schema-liquibase ged_liquibase --base-vierge | tee "$TRAVAIL/socle.log" | grep -E '^RESULTAT'
grep -q '|ECHEC|' "$TRAVAIL/socle.log" && NB_ECHEC=$((NB_ECHEC + 1))
java_source "$ICI/AnalyseurChangelogs.java" --backend "$BACKEND" | tr -d '\r' | tee "$TRAVAIL/analyse.log" | grep -E '^RESULTAT'
grep -q '|ECHEC|' "$TRAVAIL/analyse.log" && NB_ECHEC=$((NB_ECHEC + 1))

# 4. Démarrage de l'application : Hibernate valide, ne modifie rien.
if [[ "$DEMARRER" -eq 1 ]]; then
  if [[ -z "$JAR" ]]; then
    info "construction du JAR (mvn -o package -DskipTests)"
    (cd "$BACKEND" && mvn -o -B -q -DskipTests package) > "$TRAVAIL/package.log" 2>&1 || fatal "construction du JAR impossible (voir $TRAVAIL/package.log)"
    JAR="$(ls "$BACKEND"/target/*.jar | grep -v -- '-plain' | head -1)"
  fi
  info "démarrage de $JAR sur le port $PORT (compte ged_app, Liquibase désactivé)"
  # Démarrage depuis le dossier de travail : les chemins relatifs du profil dev (keystore
  # ./data/cles, stockage) ne doivent rien créer dans la copie de travail (voir ANO-E5-001).
  JAR="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"
  pushd "$TRAVAIL" >/dev/null
  DB_NAME="$BASE" DB_HOST="$PGHOST" DB_PORT="$PGPORT" DB_USER=ged_app DB_PASSWORD="${GED_APP_PASSWORD:-}" \
    java -jar "$JAR" --server.port="$PORT" --spring.liquibase.enabled=false --ged.base.nom="$BASE" \
    > "$TRAVAIL/application.log" 2>&1 &
  PID=$!
  popd >/dev/null
  etat=""
  for _ in $(seq 1 90); do
    sleep 2
    etat="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/actuator/health" || true)"
    [[ "$etat" == 200 ]] && break
    kill -0 "$PID" 2>/dev/null || break
  done
  kill "$PID" 2>/dev/null; wait "$PID" 2>/dev/null
  if [[ "$etat" == 200 ]]; then
    resultat E1-V04 OK "Application démarrée sur la base Liquibase (Hibernate validate, compte ged_app)"
  else
    resultat E1-V04 ECHEC "Application démarrée sur la base Liquibase" "$(grep -E 'Schema-validation|ERROR|Caused by' "$TRAVAIL/application.log" | head -3 | tr '\n' ' ')"
  fi
  schema_normalise "$BASE" "$TRAVAIL/schema-apres-demarrage.sql" ged ged_liquibase
  if diff -q "$TRAVAIL/schema-liquibase.sql" "$TRAVAIL/schema-apres-demarrage.sql" >/dev/null; then
    resultat E1-V05 OK "Aucune modification de schéma par l'application (DDL identique avant/après démarrage)"
  else
    resultat E1-V05 ECHEC "Aucune modification de schéma par l'application" "$(diff "$TRAVAIL/schema-liquibase.sql" "$TRAVAIL/schema-apres-demarrage.sql" | head -5 | tr '\n' ' ')"
  fi
else
  resultat E1-V04 NA "Démarrage de l'application sur la base Liquibase" "option --demarrer-application non demandée"
fi

info "traces conservées dans $TRAVAIL"
bilan "E1 base vierge $BASE"
