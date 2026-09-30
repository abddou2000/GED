#!/usr/bin/env bash
# =====================================================================
#  Démonstration de deployer.sh et de ses retours arrière (DAT 9.3, 10.1 ;
#  T-088 critère 1, T-092) sur un poste Linux SANS systemd.
#
#  RÉEL : deployer.sh, test-fumee.sh, sauvegarder-base.sh livrés, sans
#  modification ; JAR de la GED construit depuis le dépôt (profil dev,
#  annuaire embarqué) ; PostgreSQL (base jetable <nom>_deploiement) ;
#  Liquibase CLI 4.29.2 (classe principale officielle
#  LiquibaseCommandLine, liquibase-core du JAR + picocli de Maven Central) ;
#  NGINX avec deploiement/nginx/ged.conf (ports, certificat et chemins
#  réécrits) ; paquet Angular construit ; unité systemd
#  deploiement/systemd/ged-backend.service lue par le systemctl simulé.
#  SIMULÉ : systemd lui-même (deploiement/uat/systemctl-simule : pas de
#  durcissement ni de redémarrage automatique) ; les versions v2 et v3 sont
#  le même JAR auquel on ajoute un changeset de démonstration (et, pour v3,
#  un défaut de configuration qui fait échouer le test de fumée).
#
#  Scénario (chaque étape contrôlée) :
#    E0  préalables : nginx -t sur ged.conf, version de Liquibase
#    E1  première installation v1 + front f1, puis compte de fumée configuré
#    E2  déploiement v2 + f2 : sauvegarde, tag, migration, bascule, fumée
#    E3  --retour-arriere --base après un déploiement réussi : v1, f1, base au tag
#    E4  redéploiement de v2 (back seul)
#    E5  front seul f2 puis retour arrière du front : service jamais redémarré
#    E6  v3 défectueuse : fumée en échec, retour AUTOMATIQUE à v2, base migrée
#    E7  --retour-arriere --base après le retour automatique : changeset de v3 défait
#    E8  module métier désactivé puis réactivé (--desactiver-module / --activer-module)
#
#  Prérequis : root ; PostgreSQL local (compte postgres pour créer la base) ;
#  nginx, openssl, jq, zip, unzip, curl, pg_dump ; rôles ged_owner et ged_app
#  (backend/scripts/db/creer-roles.sql) et leurs mots de passe dans
#  DB_OWNER_PASSWORD et DB_PASSWORD ; JAR et paquet front construits
#  (mvn package ; ng build) ou DEMO_CONSTRUIRE=oui.
#  Variables : DEMO_BASE (défaut ged_dev2_deploiement, suffixe _deploiement
#  obligatoire : la base est SUPPRIMÉE puis recréée), SERVER_PORT,
#  GED_MANAGEMENT_PORT, GED_IDENTITE_ANNUAIRE_EMBARQUE_PORT, DEMO_PORT_HTTPS,
#  DEMO_PORT_HTTP, DEMO_REPERTOIRE, GARDER=1 (service, NGINX et répertoire
#  laissés en place).
#  Code de sortie : 0 si tous les contrôles passent. Durée : 10 à 15 min.
# =====================================================================
set -uo pipefail
DEPOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEPLOYER="$DEPOT/deploiement/scripts/deployer.sh"
BASE="${DEMO_BASE:-ged_dev2_deploiement}"
D="${DEMO_REPERTOIRE:-/tmp/ged-demo-deploiement}"
P_BACK="${SERVER_PORT:-18082}"
P_MGMT="${GED_MANAGEMENT_PORT:-$((P_BACK + 10))}"
P_LDAP="${GED_IDENTITE_ANNUAIRE_EMBARQUE_PORT:-33392}"
P_HTTPS="${DEMO_PORT_HTTPS:-$((P_BACK + 500))}"
P_HTTP="${DEMO_PORT_HTTP:-$((P_BACK + 600))}"
URL="https://localhost:$P_HTTPS"

[[ "$(id -u)" == 0 ]] || { echo "à lancer en root (comme deployer.sh)"; exit 2; }
[[ "$BASE" == *_deploiement ]] || { echo "DEMO_BASE doit finir par _deploiement (base supprimée puis recréée)"; exit 2; }
[[ "$D" == /tmp/ged-demo-* ]] || { echo "DEMO_REPERTOIRE doit commencer par /tmp/ged-demo-"; exit 2; }
: "${DB_PASSWORD:?mot de passe de ged_app attendu dans DB_PASSWORD}"
: "${DB_OWNER_PASSWORD:?mot de passe de ged_owner attendu dans DB_OWNER_PASSWORD}"
for c in nginx openssl jq zip unzip curl pg_dump psql setpriv java mvn; do
    command -v "$c" > /dev/null || { echo "commande absente : $c"; exit 2; }
done

ECHECS=0; N=0
ok() { N=$((N + 1)); echo "  [OK]    $1"; }
ko() { N=$((N + 1)); ECHECS=$((ECHECS + 1)); echo "  [ÉCHEC] $1"; }
verifier() { local libelle="$1"; shift; if "$@" > /dev/null 2>&1; then ok "$libelle"; else ko "$libelle"; fi; }
etape() { echo; echo "== $* =="; }

export SYSTEMCTL_SIMULE_UNITES="$D/unites" SYSTEMCTL_SIMULE_ETAT="$D/systemd"
export SYSTEMCTL_SIMULE_CHEMINS="/etc/ged=$D/etc-ged:/opt/ged=$D/opt-ged:/var/lib/ged=$D/var-lib-ged:/var/log/ged=$D/var-log-ged"
nettoyer() {
    [[ -x "$D/bin/systemctl" ]] && "$D/bin/systemctl" stop ged-backend > /dev/null 2>&1
    [[ -f "$D/nginx/nginx.pid" ]] && nginx -p "$D/nginx/" -c "$D/nginx/nginx.conf" -s stop > /dev/null 2>&1
    sleep 1
}
fin() {
    if [[ -n "${GARDER:-}" ]]; then echo "conservé : $D (service et NGINX en marche)"; else nettoyer; fi
}
nettoyer; rm -rf "$D"
trap fin EXIT
mkdir -p "$D"/{artefacts,bin,unites,systemd,etc-ged/front,opt-ged,var-lib-ged,var-log-ged,etat,journal,sauvegardes,liquibase/lib,nginx/tls,nginx/logs,nginx/tmp,travail}
chmod 755 "$D"

# ---------------------------------------------------------------------
# Préparation : compte de service, base, artefacts, Liquibase, configuration, NGINX
# ---------------------------------------------------------------------
etape "Préparation"
id ged > /dev/null 2>&1 || useradd --system --home-dir /opt/ged --no-create-home --shell /usr/sbin/nologin ged
chown ged:ged "$D/var-lib-ged" "$D/var-log-ged"

runuser -u postgres -- psql -X -q -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS $BASE WITH (FORCE)" \
    && runuser -u postgres -- psql -X -q -v ON_ERROR_STOP=1 -v base="$BASE" \
        -f "$DEPOT/backend/scripts/db/preparer-base.sql" > "$D/travail/base.log" 2>&1 \
    || { echo "base $BASE non préparée (voir $D/travail/base.log)"; exit 2; }
echo "  base $BASE recréée (preparer-base.sql)"

JAR_SOURCE="$(ls "$DEPOT"/backend/target/ged-*.jar 2> /dev/null | head -1)"
if [[ "${DEMO_CONSTRUIRE:-non}" == oui || -z "$JAR_SOURCE" ]]; then
    (cd "$DEPOT/backend" && mvn -B -q -ntp -DskipTests -Ddependency-check.skip=true package) || exit 2
    JAR_SOURCE="$(ls "$DEPOT"/backend/target/ged-*.jar | head -1)"
fi
FRONT_SOURCE="$DEPOT/frontend/dist/frontend/browser"
[[ -f "$FRONT_SOURCE/index.html" ]] || { echo "paquet front absent : cd frontend && npx ng build"; exit 2; }

A="$D/artefacts"; T="$D/travail"
cp "$JAR_SOURCE" "$A/ged-v1.jar"
MAITRE=BOOT-INF/classes/db/changelog/db.changelog-master.xml
ajouter_changeset() { # jar, fichier de changeset (nom), contenu
    local jar="$1" nom="$2" contenu="$3" r="$T/jar-$2"
    mkdir -p "$r/BOOT-INF/classes/db/changelog/demonstration"
    printf '%s\n' "$contenu" > "$r/BOOT-INF/classes/db/changelog/demonstration/$nom"
    unzip -p "$jar" "$MAITRE" \
        | sed "s#</databaseChangeLog>#    <include file=\"demonstration/$nom\" relativeToChangelogFile=\"true\"/>\n</databaseChangeLog>#" \
        > "$r/$MAITRE.nouveau" && mv "$r/$MAITRE.nouveau" "$r/$MAITRE"
    (cd "$r" && zip -q "$jar" "$MAITRE" "BOOT-INF/classes/db/changelog/demonstration/$nom")
}
ENTETE='<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">'
cp "$A/ged-v1.jar" "$A/ged-v2.jar"
ajouter_changeset "$A/ged-v2.jar" demonstration-v2.xml "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
$ENTETE
  <changeSet id=\"demonstration-v2\" author=\"demonstration\">
    <comment>Démonstration du déploiement (T-088) : table ajoutée par la version 2.</comment>
    <createTable tableName=\"demonstration_deploiement\">
      <column name=\"id\" type=\"INTEGER\"><constraints primaryKey=\"true\"/></column>
      <column name=\"libelle\" type=\"VARCHAR(100)\"/>
    </createTable>
    <rollback><dropTable tableName=\"demonstration_deploiement\"/></rollback>
  </changeSet>
</databaseChangeLog>"
cp "$A/ged-v2.jar" "$A/ged-v3.jar"
ajouter_changeset "$A/ged-v3.jar" demonstration-v3.xml "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
$ENTETE
  <changeSet id=\"demonstration-v3\" author=\"demonstration\">
    <comment>Démonstration : colonne ajoutée par la version 3 (expand, compatible avec la version 2).</comment>
    <addColumn tableName=\"demonstration_deploiement\"><column name=\"ajout_v3\" type=\"VARCHAR(20)\"/></addColumn>
    <rollback><dropColumn tableName=\"demonstration_deploiement\" columnName=\"ajout_v3\"/></rollback>
  </changeSet>
</databaseChangeLog>"
# Défaut de la v3 : API servie sous un autre chemin (sonde de disponibilité
# UP sur le port de management, mais connexion du test de fumée en 404).
mkdir -p "$T/v3/BOOT-INF/classes/config"
echo "server.servlet.context-path=/version-defectueuse" > "$T/v3/BOOT-INF/classes/config/application.properties"
(cd "$T/v3" && zip -q "$A/ged-v3.jar" BOOT-INF/classes/config/application.properties)
tar -czf "$A/ged-front-f1.tar.gz" -C "$FRONT_SOURCE" .
mkdir -p "$T/f2" && cp -r "$FRONT_SOURCE/." "$T/f2/" && echo "f2" > "$T/f2/version-demonstration.txt"
tar -czf "$A/ged-front-f2.tar.gz" -C "$T/f2" .
(cd "$A" && for f in *.jar *.tar.gz; do sha256sum "$f" > "$f.sha256"; done)
echo "  artefacts : $(cd "$A" && ls *.jar *.tar.gz | tr '\n' ' ')"

# Liquibase CLI : distribution officielle = liquibase-core et ses dépendances + picocli.
VERSION_LB="$(unzip -Z1 "$A/ged-v1.jar" | sed -n 's#^BOOT-INF/lib/liquibase-core-\(.*\)\.jar$#\1#p')"
(cd "$T" && unzip -q -o -j "$A/ged-v1.jar" 'BOOT-INF/lib/liquibase-core-*' 'BOOT-INF/lib/snakeyaml-*' \
    'BOOT-INF/lib/opencsv-*' 'BOOT-INF/lib/commons-*' 'BOOT-INF/lib/jaxb-*' 'BOOT-INF/lib/postgresql-*' \
    'BOOT-INF/lib/jakarta.xml.bind-*' 'BOOT-INF/lib/jakarta.activation-*' -d "$D/liquibase/lib")
PICOCLI="$(sed -n '/<artifactId>picocli<\/artifactId>/{n;s#.*<version>\(.*\)</version>.*#\1#p}' \
    < <(unzip -p "$D/liquibase/lib/liquibase-core-$VERSION_LB.jar" "META-INF/maven/org.liquibase/liquibase-core/pom.xml"))"
PICOCLI="${PICOCLI:-4.7.6}"
mvn -B -q -ntp dependency:get "-Dartifact=info.picocli:picocli:$PICOCLI" > /dev/null \
    && cp "$HOME/.m2/repository/info/picocli/picocli/$PICOCLI/picocli-$PICOCLI.jar" "$D/liquibase/lib/" \
    || { echo "picocli $PICOCLI introuvable"; exit 2; }
cat > "$D/liquibase/liquibase" <<'SH'
#!/usr/bin/env bash
exec java -cp "$(dirname "$(readlink -f "$0")")/lib/*" liquibase.integration.commandline.LiquibaseCommandLine "$@"
SH
chmod 755 "$D/liquibase/liquibase"

# Unité systemd RÉELLE, lue par le systemctl simulé.
cp "$DEPOT/deploiement/systemd/ged-backend.service" "$D/unites/"
ln -s "$DEPOT/deploiement/uat/systemctl-simule" "$D/bin/systemctl"
mkdir -p "$D/opt-ged/tessdata" && cp "$DEPOT"/backend/tessdata/*.traineddata "$D/opt-ged/tessdata/"
chmod -R a+rX "$D/opt-ged"

C="$D/etc-ged"
cat > "$C/ged.env" <<ENV
GED_PROFIL=dev
GED_ENVIRONNEMENT=dev
JAVA_OPTS="-Xms256m -Xmx1g -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=$D/var-lib-ged/tmpfs/java -Dfile.encoding=UTF-8 -Duser.timezone=UTC"
SERVER_PORT=$P_BACK
SERVER_ADDRESS=127.0.0.1
GED_MANAGEMENT_PORT=$P_MGMT
GED_MANAGEMENT_ADRESSE=127.0.0.1
GED_PROXYS_DE_CONFIANCE=127.0.0.1
GED_ORIGINES=$URL
DB_HOST=localhost
DB_PORT=5432
DB_NAME=$BASE
DB_SCHEMA=ged
DB_USER=ged_app
DB_PASSWORD=$DB_PASSWORD
SPRING_LIQUIBASE_ENABLED=false
SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=3
GED_STOCKAGE_RACINE=$D/var-lib-ged/fichiers
GED_CACHE_APERCU_RACINE=$D/var-lib-ged/cache-apercu
GED_KEYSTORE_CHEMIN=$D/var-lib-ged/cles/ged-kek.p12
GED_KEYSTORE_MDP=demonstration-locale
SPRING_SERVLET_MULTIPART_LOCATION=$D/var-lib-ged/tmpfs/multipart
GED_APERCU_TRAVAIL=$D/var-lib-ged/tmpfs/apercu
GED_TESSDATA=$D/opt-ged/tessdata
GED_LOG_REPERTOIRE=$D/var-log-ged
GED_AUDIT_EXPORT_SCELLEMENTS=$D/var-lib-ged/audit-scellements/scellements.jsonl
GED_IDENTITE_ANNUAIRE_EMBARQUE_PORT=$P_LDAP
GED_LDAP_URLS=ldap://localhost:$P_LDAP
GED_URL_APPLICATION=$URL
ENV
mkdir -p "$D/var-lib-ged/audit-scellements" && chown -R ged:ged "$D/var-lib-ged"
cat > "$C/liquibase.env" <<ENV
DB_HOST=localhost
DB_PORT=5432
DB_NAME=$BASE
DB_SCHEMA=ged
DB_SCHEMA_LIQUIBASE=ged_liquibase
DB_OWNER_USER=ged_owner
DB_OWNER_PASSWORD=$DB_OWNER_PASSWORD
DB_SSLMODE=prefer
ENV
# Compte de l'annuaire de DÉMONSTRATION embarqué (application-dev.yml) : pas un secret.
cat > "$C/deploiement.env" <<ENV
GED_URL_PUBLIQUE=$URL
GED_URL_MANAGEMENT=http://127.0.0.1:$P_MGMT
GED_FUMEE_IDENTIFIANT=sbennani
GED_FUMEE_MDP=dev-local-only
GED_FUMEE_TYPE_DOCUMENT=00000000-0000-0000-0000-000000000000
LIQUIBASE_CMD=$D/liquibase/liquibase
ENV
echo "localhost:5432:$BASE:ged_owner:$DB_OWNER_PASSWORD" > "$C/sauvegarde.pgpass"
cat > "$C/sauvegarde.env" <<ENV
PGHOST=localhost
PGPORT=5432
PGDATABASE=$BASE
PGUSER=ged_owner
PGPASSFILE=$C/sauvegarde.pgpass
PGSSLMODE=prefer
GED_SAUVEGARDE_DESTINATION=$D/sauvegardes
GED_JOURNAL=$D/var-log-ged/sauvegardes.log
ENV
cp "$DEPOT/deploiement/nginx/config.json.exemple" "$C/front/config.json"
chmod 0600 "$C/sauvegarde.pgpass"; chmod 0400 "$C"/*.env; chown ged:ged "$C/ged.env"
chmod 755 "$C" "$C/front"; chmod 644 "$C/front/config.json"

# NGINX : ged.conf livré, seuls ports, certificat, chemins et amont réécrits.
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=localhost" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" \
    -keyout "$D/nginx/tls/ged.key" -out "$D/nginx/tls/ged.crt" > /dev/null 2>&1
sed -e "s#server ged-app.marchica.local:8080;#server 127.0.0.1:$P_BACK;#" \
    -e "/listen \[::\]/d" \
    -e "s#listen 80;#listen 127.0.0.1:$P_HTTP;#" \
    -e "s#listen 443 ssl http2;#listen 127.0.0.1:$P_HTTPS ssl http2;#" \
    -e "s#/etc/nginx/tls/ged.fullchain.pem#$D/nginx/tls/ged.crt#" \
    -e "s#/etc/nginx/tls/ged.key#$D/nginx/tls/ged.key#" \
    -e "s#/var/log/nginx/#$D/nginx/logs/#g" \
    -e "s#/opt/ged/front/courant#$D/opt-ged/front/courant#" \
    -e "s#/etc/ged/front/config.json#$C/front/config.json#" \
    "$DEPOT/deploiement/nginx/ged.conf" > "$D/nginx/ged.conf"
cat > "$D/nginx/nginx.conf" <<CONF
worker_processes 1;
pid $D/nginx/nginx.pid;
error_log $D/nginx/logs/error.log warn;
events { worker_connections 256; }
http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;
    client_body_temp_path $D/nginx/tmp/body;
    proxy_temp_path $D/nginx/tmp/proxy;
    fastcgi_temp_path $D/nginx/tmp/fastcgi;
    uwsgi_temp_path $D/nginx/tmp/uwsgi;
    scgi_temp_path $D/nginx/tmp/scgi;
    include $D/nginx/ged.conf;
}
CONF
export CURL_CA_BUNDLE="$D/nginx/tls/ged.crt"

export GED_CONF_DIR="$C" GED_RACINE="$D/opt-ged" GED_ETAT_DIR="$D/etat" GED_JOURNAL="$D/journal/deploiements.log"
export GED_DELAI_SANTE_S=300 PATH="$D/bin:$PATH"
deployer() { # journal, arguments de deployer.sh
    local j="$1"; shift
    echo "  \$ deployer.sh $*"
    "$DEPLOYER" "$@" > "$D/journal/$j.log" 2>&1
}
psql_base() { PGPASSWORD="$DB_OWNER_PASSWORD" psql -X -h localhost -U ged_owner -d "$BASE" -Atc "$1" 2> /dev/null; }
table_existe() { [[ "$(psql_base "select to_regclass('ged.demonstration_deploiement') is not null")" == t ]]; }
colonne_v3() { [[ "$(psql_base "select count(*) from information_schema.columns where table_schema='ged' and table_name='demonstration_deploiement' and column_name='ajout_v3'")" == 1 ]]; }
jar_actif() { [[ "$(readlink -f "$D/opt-ged/backend/ged.jar")" == "$(cat "$D/etat/versions/$1")" ]]; }
noter_jar() { mkdir -p "$D/etat/versions"; readlink -f "$D/opt-ged/backend/ged.jar" > "$D/etat/versions/$1"; }
pid_service() { cat "$D/systemd/ged-backend.pid" 2> /dev/null; }
fichier_f2() { [[ "$(curl -fsS "$URL/version-demonstration.txt" 2> /dev/null)" == f2 ]]; }
contient() { grep -q -- "$2" "$D/journal/$1.log"; }

# ---------------------------------------------------------------------
etape "E0 — préalables"
verifier "nginx -t sur deploiement/nginx/ged.conf (réécrit pour le poste)" nginx -t -p "$D/nginx/" -c "$D/nginx/nginx.conf"
nginx -p "$D/nginx/" -c "$D/nginx/nginx.conf" && ok "NGINX démarré (https://localhost:$P_HTTPS)" || ko "NGINX non démarré"
VERSION_CLI="$("$D/liquibase/liquibase" --version 2> /dev/null | sed -n 's/^Liquibase Version: *//p' | head -1)"
[[ "$VERSION_CLI" == "$VERSION_LB" ]] && ok "Liquibase CLI $VERSION_CLI = liquibase-core du JAR" || ko "Liquibase CLI « $VERSION_CLI » ≠ $VERSION_LB"

# ---------------------------------------------------------------------
etape "E1 — première installation (v1, f1)"
# Le type documentaire du compte de fumée n'existe qu'après le premier
# démarrage (amorçage) : comme en installation réelle, le premier passage
# se fait sans retour automatique, puis l'administrateur renseigne
# GED_FUMEE_TYPE_DOCUMENT et relance les vérifications.
deployer E1 dev --jar "$A/ged-v1.jar" --front "$A/ged-front-f1.tar.gz" --sans-retour-auto
contient E1 "Liquibase : update" && ! contient E1 "migration en échec" && ok "base vierge : validate, tag, update complet" || ko "migration initiale (voir E1.log)"
verifier "service actif (unité ged-backend.service, compte ged)" systemctl is-active --quiet ged-backend
contient E1 "Sonde de disponibilité : UP" && ok "sonde de disponibilité UP" || ko "sonde de disponibilité (E1.log)"
contient E1 "\[OK\] connexion" && ok "test de fumée : front, en-têtes, connexion par l'identifiant d'annuaire" || ko "connexion du test de fumée (E1.log)"
noter_jar v1
TYPE="$(psql_base "select id from ged.type_document order by created_at, id limit 1")"
[[ -n "$TYPE" ]] && ok "type documentaire amorcé : $TYPE" || ko "aucun type documentaire amorcé"
chmod 600 "$C/deploiement.env"; sed -i "s/^GED_FUMEE_TYPE_DOCUMENT=.*/GED_FUMEE_TYPE_DOCUMENT=$TYPE/" "$C/deploiement.env"; chmod 400 "$C/deploiement.env"
deployer E1-verifier dev --verifier && ok "--verifier : test de fumée complet (connexion, dépôt, recherche)" || ko "--verifier après installation (E1-verifier.log)"
[[ "$(stat -c %U "$D/systemd/ged-backend.pid" 2> /dev/null)" ]] && ps -o user= -p "$(pid_service)" | grep -q '^ged' \
    && ok "JVM exécutée sous le compte ged (User= de l'unité)" || ko "JVM hors du compte ged"

# ---------------------------------------------------------------------
etape "E2 — déploiement v2 + f2 (migration)"
deployer E2 dev --jar "$A/ged-v2.jar" --front "$A/ged-front-f2.tar.gz" && ok "déploiement réussi" || ko "déploiement v2 (E2.log)"
ls "$D"/sauvegardes/base/*avant-deploiement-*/TERMINE > /dev/null 2>&1 && ok "sauvegarde préalable terminée (pg_dump)" || ko "sauvegarde préalable absente"
contient E2 "point de retour « deploiement-" && ok "Liquibase : validate, tag, update" || ko "tag Liquibase (E2.log)"
verifier "table de la v2 créée" table_existe
noter_jar v2
verifier "front f2 servi par NGINX" fichier_f2
contient E2 "Test de fumée réussi" && ok "test de fumée (connexion, dépôt, recherche)" || ko "test de fumée (E2.log)"

# ---------------------------------------------------------------------
etape "E3 — retour arrière manuel avec la base, après un déploiement réussi"
deployer E3 dev --retour-arriere --base && ok "retour arrière terminé" || ko "retour arrière (E3.log)"
table_existe && ko "table de la v2 encore présente" || ok "base ramenée au tag : table de la v2 supprimée"
verifier "JAR v1 actif" jar_actif v1
fichier_f2 && ko "front f2 encore servi" || ok "front f1 rétabli"
contient E3 "Test de fumée réussi" && ok "vérifications de la version rétablie" || ko "vérifications après retour (E3.log)"

# ---------------------------------------------------------------------
etape "E4 — redéploiement de v2 (back seul)"
deployer E4 dev --jar "$A/ged-v2.jar" --module back && ok "déploiement réussi" || ko "redéploiement v2 (E4.log)"
verifier "table de la v2 recréée" table_existe
noter_jar v2

# ---------------------------------------------------------------------
etape "E5 — front seul, puis retour arrière du front"
PID_AVANT="$(pid_service)"
deployer E5 dev --front "$A/ged-front-f2.tar.gz" --module front && ok "front f2 déployé" || ko "déploiement du front (E5.log)"
verifier "front f2 servi" fichier_f2
deployer E5-retour dev --retour-arriere --module front && ok "retour arrière du front" || ko "retour du front (E5-retour.log)"
fichier_f2 && ko "front f2 encore servi" || ok "front f1 rétabli"
[[ "$(pid_service)" == "$PID_AVANT" ]] && ok "back-end jamais redémarré (PID $PID_AVANT)" || ko "back-end redémarré par une opération sur le front"

# ---------------------------------------------------------------------
etape "E6 — version défectueuse v3 : retour arrière AUTOMATIQUE"
deployer E6 dev --jar "$A/ged-v3.jar" --module back && ko "le déploiement de v3 aurait dû échouer" || ok "déploiement de v3 refusé (code non nul)"
contient E6 "Vérifications post-déploiement en ÉCHEC" && ok "test de fumée en échec détecté" || ko "échec non détecté (E6.log)"
contient E6 "RETOUR ARRIÈRE" && ok "retour arrière automatique déclenché" || ko "pas de retour automatique (E6.log)"
verifier "JAR v2 de nouveau actif" jar_actif v2
verifier "base laissée migrée (colonne de la v3, expand compatible v2)" colonne_v3
JAR_V3="$(ls -1dt "$D"/opt-ged/backend/versions/*/ | head -1)ged.jar"
deployer E6-verifier dev --verifier && ok "v2 en service, vérifications vertes" || ko "vérifications après retour automatique (E6-verifier.log)"

# ---------------------------------------------------------------------
etape "E7 — défaire la migration de v3 (--retour-arriere --base)"
deployer E7 dev --retour-arriere --base && ok "retour arrière avec la base terminé" || ko "retour arrière --base (E7.log)"
contient E7 "(changesets de $JAR_V3)" && ok "rollback lancé avec le JAR qui a migré (v3), pas avec le JAR actif (v2)" \
    || ko "rollback lancé avec un autre JAR que celui de la migration (E7.log)"
colonne_v3 && ko "colonne de la v3 encore présente" || ok "changeset de la v3 défait"
verifier "table de la v2 conservée (point de retour = avant v3)" table_existe
verifier "JAR v2 actif" jar_actif v2

# ---------------------------------------------------------------------
etape "E8 — module métier désactivé puis réactivé (DAT 9.3)"
deployer E8 dev --desactiver-module workflow && ok "module workflow désactivé, vérifié" || ko "désactivation (E8.log)"
code="$(curl -s -o "$T/workflow.json" -w '%{http_code}' "$URL/api/v1/workflow/regles")"
[[ "$code" == 404 ]] && grep -q MODULE_INACTIF "$T/workflow.json" && ok "route du module fermée via NGINX (404 MODULE_INACTIF)" || ko "route du module : $code"
deployer E8-activer dev --activer-module workflow && ok "module workflow réactivé, vérifié" || ko "réactivation (E8-activer.log)"
code="$(curl -s -o /dev/null -w '%{http_code}' "$URL/api/v1/workflow/regles")"
[[ "$code" == 401 ]] && ok "route du module rouverte (401 sans jeton)" || ko "route du module après réactivation : $code"

etape "Journal"
echo "  $(grep -c '' "$GED_JOURNAL") lignes dans $GED_JOURNAL ; appels systemctl : $(grep -c '' "$D/systemd/systemctl.log")"
echo
if [[ "$ECHECS" -eq 0 ]]; then echo "Démonstration de deployer.sh : $N contrôles, tous verts."
else echo "Démonstration de deployer.sh : $ECHECS contrôle(s) en échec sur $N (journaux : $D/journal)."; fi
exit $((ECHECS > 0))
