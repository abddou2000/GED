#!/usr/bin/env bash
# =====================================================================
#  Recette T-092 (DAT 10.1, 4.2.2) — deployer.sh exécuté de bout en bout
#  SANS systemd ni application, vague 8.
#
#  Réel : deployer.sh, test-fumee.sh, commun.sh, sauvegarder-base.sh LIVRÉS,
#  sans modification ; PostgreSQL (base ged_qa_v8_deploy, compte ged_owner) ;
#  liquibase-core 4.29.2 embarqué par le backend (LiquibaseCli.java, qui lit
#  les mêmes variables LIQUIBASE_* que la CLI officielle).
#  Simulé : systemctl, install -o/-g, jq (bouchons/), la GED en marche
#  (ServeurGedSimule.java : sonde, front, connexion au contrat RÉEL
#  « identifiant », dépôt, recherche), les JAR (changelog réel + liquibase-core).
#
#  Scénarios :
#    A. syntaxe (bash -n) de tous les scripts de deploiement/ ;
#    B. garde-fous : environnement ≠ serveur, configuration lisible par tous ;
#    C. preuve du contrat de connexion (classes LIVRÉES, Jackson + validation) ;
#    D1. 1er déploiement (base vierge) — GED simulée tolérante ;
#    D2. changelog altéré : validate en échec, rien n'est arrêté ni migré ;
#    D3. déploiement v2 avec le contrat réel : fumée en échec → retour auto ;
#    D4. --retour-arriere --base juste après D3 (lien = JAR précédent) ;
#    D5. témoin : v3 déployée puis --retour-arriere --base (lien = JAR récent) ;
#    D6. --verifier contre le contrat réel.
#
#  Usage : V8_TRAVAIL=<dossier hors dépôt> V8_PORT=18556 recette-t092.sh
# =====================================================================
set -Eeuo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib-v8.sh"

BASE=ged_qa_v8_deploy
W="$V8_TRAVAIL/t092"
PORT="${V8_PORT:-18556}"
case "$PORT" in 18084|18094|33394) fatal "port $PORT réservé à une autre recette" ;; esac
rm -rf "$W"; mkdir -p "$W"/{conf/front,racine,etat,journal,sauvegardes,jars,simule,bouchon-etat}
export MSYS=winsymlinks:nativestrict      # vrais liens symboliques (bascule atomique)
export BOUCHON_ETAT="$W/bouchon-etat"
export PATH="$V8_DIR/bouchons:$PATH"
export TMPDIR="${TMPDIR:-$W}"
DEPLOYER="$SCRIPTS/deployer.sh"
JOURNAL="$W/journal/deploiements.log"

# ---------------------------------------------------------------------
# A. Syntaxe
# ---------------------------------------------------------------------
ko=""
for f in "$DEPOT"/deploiement/scripts/*.sh "$DEPOT"/deploiement/sauvegarde/*.sh; do bash -n "$f" 2>>"$W/bash-n.log" || ko="$ko $(basename "$f")"; done
[[ -z "$ko" ]] && resultat T092-01 OK "bash -n : tous les scripts de deploiement/scripts et deploiement/sauvegarde" "$(ls "$DEPOT"/deploiement/scripts/*.sh "$DEPOT"/deploiement/sauvegarde/*.sh | wc -l) scripts" \
  || resultat T092-01 ECHEC "erreurs de syntaxe" "$ko"

# ---------------------------------------------------------------------
# Préparation : base vierge, configuration, JAR, CLI, GED simulée
# ---------------------------------------------------------------------
supprimer_base "$BASE"
"$PSQL" -X -q -U postgres -d postgres -v base="$BASE" -f "$DEPOT/backend/scripts/db/preparer-base.sql" >/dev/null
( cd "$DEPOT/backend" && mvn -o -B -q dependency:build-classpath -Dmdep.outputFile="$(cygpath -w "$W/cp.txt")" -Dmdep.includeScope=runtime ) >&2 \
  || fatal "classpath du backend introuvable hors ligne"
export V8_LB_CP="$(cat "$W/cp.txt")"
VERSION_LB="$(ls "$HOME/.m2/repository/org/liquibase/liquibase-core/" | sort -V | tail -1)"
grep -q "liquibase-core-$VERSION_LB.jar" "$W/cp.txt" || VERSION_LB="$(grep -o 'liquibase-core-[0-9.]*\.jar' "$W/cp.txt" | head -1 | sed 's/liquibase-core-//; s/\.jar//')"

fabriquer_jar() {  # nom [fichier de changeset supplémentaire…] ; ALTERER=oui modifie un changeset déjà appliqué
  local nom="$1"; shift
  local d="$W/jars/src-$nom"
  mkdir -p "$d/BOOT-INF/classes" "$d/BOOT-INF/lib"
  cp -r "$DEPOT/backend/src/main/resources/db" "$d/BOOT-INF/classes/"
  : > "$d/BOOT-INF/lib/liquibase-core-$VERSION_LB.jar"
  local master="$d/BOOT-INF/classes/db/changelog/db.changelog-master.xml" cs
  for cs in "$@"; do
    cp "$cs" "$d/BOOT-INF/classes/db/changelog/changesets/"
    sed -i "s#</databaseChangeLog>#    <include file=\"changesets/$(basename "$cs")\" relativeToChangelogFile=\"true\"/>\n</databaseChangeLog>#" "$master"
  done
  if [[ "${ALTERER:-non}" == oui ]]; then
    sed -i 's/defaultValue="AES-256-GCM"/defaultValue="AES-256-GCM-ALTERE"/' "$d/BOOT-INF/classes/db/changelog/changesets/202609271200_creation_table_cle_fichier.xml"
  fi
  (cd "$d" && jar cf "$(cygpath -w "$W/jars/ged-$nom.jar")" BOOT-INF)
  (cd "$W/jars" && sha256sum "ged-$nom.jar" > "ged-$nom.jar.sha256")
}
changeset_essai() {  # horodatage nom_table → fichier de changeset réversible
  local f="$W/jars/${1}_$2.xml"
  cat > "$f" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd"
        logicalFilePath="db/changelog/changesets/${1}_$2.xml">
    <changeSet id="$1-$2" author="recette-v8">
        <createTable tableName="$2"><column name="id" type="INT"/></createTable>
        <rollback><dropTable tableName="$2"/></rollback>
    </changeSet>
</databaseChangeLog>
XML
  echo "$f"
}
CS1="$(changeset_essai 209912311200 qa_v8_essai)"
CS2="$(changeset_essai 209912311201 qa_v8_essai_bis)"
fabriquer_jar v1
fabriquer_jar v2 "$CS1"
fabriquer_jar v3 "$CS1" "$CS2"
ALTERER=oui fabriquer_jar altere
mkdir -p "$W/front-src" && echo '<!doctype html><title>GED</title>' > "$W/front-src/index.html"
tar -czf "$W/jars/ged-front.tar.gz" -C "$W/front-src" . && (cd "$W/jars" && sha256sum ged-front.tar.gz > ged-front.tar.gz.sha256)
echo '{"demo":false,"apiUrl":"/api"}' > "$W/conf/front/config.json"

cat > "$W/conf/ged.env" <<ENV
GED_ENVIRONNEMENT=dev
GED_STOCKAGE_RACINE=$W/stockage
ENV
cat > "$W/conf/deploiement.env" <<ENV
GED_URL_PUBLIQUE=http://127.0.0.1:$PORT
GED_URL_MANAGEMENT=http://127.0.0.1:$PORT
GED_FUMEE_IDENTIFIANT=svc-fumee
GED_FUMEE_MDP=fumee-factice
GED_FUMEE_TYPE_DOCUMENT=00000000-0000-4000-8000-000000000001
LIQUIBASE_CMD=$V8_DIR/bouchons/liquibase
ENV
cat > "$W/conf/liquibase.env" <<ENV
DB_HOST=localhost
DB_PORT=5432
DB_NAME=$BASE
DB_SCHEMA=ged
DB_SCHEMA_LIQUIBASE=ged_liquibase
DB_OWNER_USER=ged_owner
DB_OWNER_PASSWORD=inutilise-authentification-trust
DB_SSLMODE=disable
ENV
cat > "$W/conf/sauvegarde.env" <<ENV
PGHOST=localhost
PGPORT=5432
PGUSER=postgres
PGDATABASE=$BASE
GED_SAUVEGARDE_DESTINATION=$W/sauvegardes
ENV
chmod 0644 "$W"/conf/*.env

export GED_CONF_DIR="$W/conf" GED_RACINE="$W/racine" GED_ETAT_DIR="$W/etat" GED_JOURNAL="$JOURNAL" \
       GED_SANS_ROOT=oui GED_DELAI_SANTE_S=15
mkdir -p "$W/racine/backend/versions" "$W/racine/front/versions"

echo UP > "$W/simule/sante"; echo tolerant > "$W/simule/contrat"
java -Dfile.encoding=UTF-8 "$(cygpath -w "$V8_DIR/ServeurGedSimule.java")" "$PORT" "$(cygpath -w "$W/simule")" > "$W/simule/serveur.log" 2>&1 &
PID_SRV=$!
trap 'kill $PID_SRV 2>/dev/null || true' EXIT
for _ in $(seq 1 30); do curl -fsS "http://127.0.0.1:$PORT/actuator/health/readiness" >/dev/null 2>&1 && break; sleep 1; done
curl -fsS "http://127.0.0.1:$PORT/actuator/health/readiness" >/dev/null || fatal "GED simulée injoignable sur $PORT"

deployer() {  # journal séparé par scénario ; code de sortie conservé
  local scen="$1"; shift
  echo "=== $scen : deployer.sh $* ===" >> "$JOURNAL"
  set +e; "$DEPLOYER" "$@" > "$W/$scen.log" 2>&1; local c=$?; set -e
  echo "$c"
}
nb_changesets() { q "$BASE" "select count(*) from ged_liquibase.databasechangelog" 2>/dev/null || echo 0; }
table_existe() { q "$BASE" "select to_regclass('ged.$1') is not null"; }
lien_jar() { basename "$(dirname "$(readlink -f "$W/racine/backend/ged.jar")")"; }
jar_de_version() { cmp -s "$(readlink -f "$W/racine/backend/ged.jar")" "$W/jars/ged-$1.jar"; }

# ---------------------------------------------------------------------
# B. Garde-fous
# ---------------------------------------------------------------------
c="$(GED_IGNORER_PERMISSIONS=non deployer B1 dev --verifier)"
[[ "$c" != 0 ]] && grep -q "doit être en 0400" "$W/B1.log" \
  && resultat T092-02 OK "configuration lisible par d'autres (0644) : refusée" "$(grep -o 'ÉCHEC.*' "$W/B1.log" | head -1)" \
  || resultat T092-02 ECHEC "configuration 0644 acceptée" "code $c"
export GED_IGNORER_PERMISSIONS=oui
c="$(deployer B2 uat --verifier)"
[[ "$c" != 0 ]] && grep -q "configuré pour « dev »" "$W/B2.log" \
  && resultat T092-03 OK "serveur DEV visé comme UAT : refusé" "$(grep -o 'ÉCHEC.*' "$W/B2.log" | head -1)" \
  || resultat T092-03 ECHEC "garde-fou d'environnement inopérant" "code $c"
c="$(deployer B3 dev --jar "$W/jars/absent.jar" --module back)"
[[ "$c" != 0 ]] && grep -q "artefact introuvable" "$W/B3.log" && resultat T092-04 OK "artefact absent : refusé avant toute action"

# ---------------------------------------------------------------------
# C. Preuve du contrat de connexion avec les classes LIVRÉES
# ---------------------------------------------------------------------
java -Dfile.encoding=UTF-8 -cp "$V8_LB_CP;$(cygpath -w "$DEPOT/backend/target/classes")" \
  "$(cygpath -w "$V8_DIR/PreuveContratConnexion.java")" > "$W/contrat.txt" 2>&1 || true
if grep -q '^CONTRAT|test-fumee.sh.*REFUSÉ 400' "$W/contrat.txt" && grep -q '^CONTRAT|client de recette.*ACCEPTÉ' "$W/contrat.txt"; then
  resultat T092-05 ECHEC "test-fumee.sh envoie {email, motDePasse} : le back-end livré répond 400 (identifiant obligatoire)" "$(grep '^CONTRAT|test-fumee' "$W/contrat.txt" | cut -d'|' -f3-)"
else
  resultat T092-05 AVERT "preuve du contrat non concluante" "$(tail -3 "$W/contrat.txt" | tr '\n' ' ')"
fi

# ---------------------------------------------------------------------
# D1. Premier déploiement sur base vierge (GED simulée tolérante)
# ---------------------------------------------------------------------
c="$(deployer D1 dev --jar "$W/jars/ged-v1.jar" --front "$W/jars/ged-front.tar.gz")"
etapes="$(grep -o -E 'Sauvegarde préalable|Liquibase : validate|base vierge|point de retour|Liquibase : update|Arrêt progressif|Back-end installé|Front-end installé|Démarrage de|Sonde de disponibilité : UP|Test de fumée réussi|Déploiement réussi' "$W/D1.log" | uniq | tr '\n' '>' )"
if [[ "$c" == 0 && "$(nb_changesets)" -gt 90 ]] && jar_de_version v1; then
  resultat T092-10 OK "1er déploiement (base vierge) de bout en bout" "enchaînement : $etapes ; $(nb_changesets) changesets par ged_owner ; sauvegarde $(ls "$W/sauvegardes/base" | head -1)"
else
  resultat T092-10 ECHEC "1er déploiement en échec" "code $c ; $(grep -o 'ÉCHEC.*' "$W/D1.log" | head -1)"
fi
q "$BASE" "select distinct tableowner from pg_tables where schemaname = 'ged'" > "$W/proprietaires.txt"
[[ "$(cat "$W/proprietaires.txt")" == ged_owner ]] && resultat T092-11 OK "objets créés par ged_owner (migration par le compte propriétaire)" || resultat T092-11 ECHEC "propriétaires inattendus" "$(tr '\n' ' ' < "$W/proprietaires.txt")"
[[ -f "$BOUCHON_ETAT/systemctl.log" ]] && info "   systemctl : $(tr '\n' ';' < "$BOUCHON_ETAT/systemctl.log")"

# ---------------------------------------------------------------------
# D2. Changelog altéré : validate doit tout arrêter
# ---------------------------------------------------------------------
avant_cs="$(nb_changesets)"; avant_sys="$(cat "$BOUCHON_ETAT/systemctl.log" 2>/dev/null | wc -l)"; avant_tag="$(cat "$W/etat/tag-liquibase" 2>/dev/null || echo aucun)"
c="$(deployer D2 dev --jar "$W/jars/ged-altere.jar" --module back)"
if [[ "$c" != 0 ]] && grep -q "changelog invalide : rien n'a été modifié" "$W/D2.log" && [[ "$(nb_changesets)" == "$avant_cs" \
      && "$(cat "$BOUCHON_ETAT/systemctl.log" 2>/dev/null | wc -l)" == "$avant_sys" && "$(cat "$W/etat/tag-liquibase" 2>/dev/null || echo aucun)" == "$avant_tag" ]] && jar_de_version v1; then
  resultat T092-12 OK "validate en échec (somme de contrôle modifiée) : arrêt AVANT tag, update, arrêt du service et installation" "$(grep -o 'ÉCHEC.*' "$W/D2.log" | head -1) ; $(grep -o 'ERREUR LIQUIBASE[^|]*|[^|]*' "$W/D2.log" | head -1 | cut -c1-160)"
else
  resultat T092-12 ECHEC "validate en échec mal traité" "code $c"
fi
grep -q 'Sauvegarde préalable' "$W/D2.log" && info "   D2 : la sauvegarde préalable a été prise avant validate (ordre DAT 10.1 : sauvegarde puis migration)"

# ---------------------------------------------------------------------
# D3. v2 avec le contrat de connexion RÉEL : fumée en échec → retour auto
# ---------------------------------------------------------------------
echo reel > "$W/simule/contrat"
: > "$W/simule/requetes.log"
c="$(deployer D3 dev --jar "$W/jars/ged-v2.jar" --module back)"
login="$(grep -m1 'auth/login' "$W/simule/requetes.log" || true)"
if [[ "$c" != 0 ]] && grep -q "connexion du compte de fumée refusée" "$W/D3.log" && grep -q "RETOUR ARRIÈRE" "$W/D3.log" && jar_de_version v1 \
      && [[ "$(table_existe qa_v8_essai)" == t ]]; then
  resultat T092-13 ECHEC "déploiement v2 : test de fumée en échec à la connexion (contrat réel) → retour arrière automatique vers v1, base laissée migrée" "requête : $login ; puis « $(grep -o 'ÉCHEC.*' "$W/D3.log" | tail -1) »"
else
  resultat T092-13 AVERT "scénario D3 inattendu" "code $c ; $(grep -o 'ÉCHEC.*' "$W/D3.log" | tail -1)"
fi
grep -q "la version précédente ne passe pas les vérifications" "$W/D3.log" \
  && info "   D3 : la version PRÉCÉDENTE échoue aussi (même test de fumée) → « intervention manuelle »"

# ---------------------------------------------------------------------
# D4. --retour-arriere --base juste après le retour automatique
# ---------------------------------------------------------------------
echo tolerant > "$W/simule/contrat"
tag="$(cat "$W/etat/tag-liquibase")"
c="$(deployer D4 dev --retour-arriere --base)"
if [[ "$(table_existe qa_v8_essai)" == t ]]; then
  resultat T092-14 ECHEC "--retour-arriere --base après un retour automatique : le changeset de v2 n'est PAS défait (rollback exécuté avec le JAR v1, qui ne le connaît pas)" "code $c ; tag $tag ; table ged.qa_v8_essai toujours présente ; $(grep -o -E 'ÉCHEC.*|ERREUR LIQUIBASE.{0,160}|Retour arrière terminé' "$W/D4.log" | head -2 | tr '\n' ' ')"
else
  resultat T092-14 OK "--retour-arriere --base après retour automatique : changeset v2 défait" "code $c"
fi
# Remise en état pour le témoin : rollback avec le JAR v2 (celui qui porte le changeset).
if [[ "$(table_existe qa_v8_essai)" == t ]]; then
  ex="$(mktemp -d)"; (cd "$ex" && unzip -q "$W/jars/ged-v2.jar" 'BOOT-INF/classes/*')
  LIQUIBASE_COMMAND_URL="jdbc:postgresql://localhost:5432/$BASE?sslmode=disable" LIQUIBASE_COMMAND_USERNAME=ged_owner \
  LIQUIBASE_COMMAND_PASSWORD=x LIQUIBASE_COMMAND_CHANGELOG_FILE=db/changelog/db.changelog-master.xml \
  LIQUIBASE_COMMAND_DEFAULT_SCHEMA_NAME=ged LIQUIBASE_LIQUIBASE_SCHEMA_NAME=ged_liquibase LIQUIBASE_SEARCH_PATH="$ex/BOOT-INF/classes" \
    "$V8_DIR/bouchons/liquibase" rollback --tag="$tag" > "$W/D4-remise.log" 2>&1 || true
  rm -rf "$ex"
  [[ "$(table_existe qa_v8_essai)" == f ]] && info "   le même rollback exécuté avec le JAR v2 défait bien le changeset (preuve que le JAR choisi est en cause)"
fi

# ---------------------------------------------------------------------
# D5. Témoin nominal : v3 déployée (fumée tolérante) puis --retour-arriere --base
# ---------------------------------------------------------------------
c="$(deployer D5a dev --jar "$W/jars/ged-v3.jar" --module back)"
ok_d5a=$([[ "$c" == 0 && "$(table_existe qa_v8_essai_bis)" == t ]] && jar_de_version v3 && echo oui || echo non)
c="$(deployer D5b dev --retour-arriere --base)"
if [[ "$ok_d5a" == oui && "$c" == 0 && "$(table_existe qa_v8_essai)" == f && "$(table_existe qa_v8_essai_bis)" == f ]] && jar_de_version v1; then
  resultat T092-15 OK "témoin : déploiement v3 réussi puis --retour-arriere --base : 2 changesets défaits jusqu'au tag, artefact précédent rétabli" "$(grep -o 'rollback jusqu.*' "$W/D5b.log" | head -1) ; lien → $(lien_jar)"
else
  resultat T092-15 ECHEC "retour arrière nominal en échec" "D5a=$ok_d5a code=$c essai=$(table_existe qa_v8_essai) bis=$(table_existe qa_v8_essai_bis) lien=$(lien_jar)"
fi

# ---------------------------------------------------------------------
# D6. --verifier contre le contrat réel
# ---------------------------------------------------------------------
echo reel > "$W/simule/contrat"
c="$(deployer D6 dev --verifier)"
[[ "$c" != 0 ]] && grep -q "connexion du compte de fumée refusée" "$W/D6.log" \
  && resultat T092-16 ECHEC "deployer.sh --verifier échoue toujours contre le contrat de connexion réel" "$(grep -o 'ÉCHEC.*' "$W/D6.log" | tail -1)" \
  || resultat T092-16 OK "--verifier réussi" "code $c"

info "Journal : $JOURNAL ; requêtes simulées : $W/simule/requetes.log"
bilan "recette-t092"
