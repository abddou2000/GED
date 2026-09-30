#!/usr/bin/env bash
# =====================================================================
#  deployer.sh — déploiement de la GED, IDENTIQUE en DEV, UAT et PROD
#  (DAT 10.1). Seule la configuration du serveur varie (/etc/ged/*.env).
#
#  Usage :
#    deployer.sh <dev|uat|prod> --jar ged.jar [--front front.tar.gz] [--module back|front|tout]
#    deployer.sh <dev|uat|prod> --retour-arriere [--base]
#    deployer.sh <dev|uat|prod> --verifier
#    deployer.sh <dev|uat|prod> --modules
#    deployer.sh <dev|uat|prod> --activer-module <code> | --desactiver-module <code>
#
#  Étapes d'un déploiement (DAT 10.1) :
#    1. contrôles préalables (environnement, artefacts, empreintes) ;
#    2. sauvegarde préalable de la base ;
#    3. migration Liquibase par ged_owner : validate, tag, puis update ;
#    4. arrêt progressif du service ;
#    5. installation de l'artefact (lien symbolique vers la version) ;
#    6. redémarrage du service ;
#    7. vérifications : sonde de santé, puis test de fumée (connexion,
#       dépôt, recherche) ;
#    8. en cas d'échec de l'étape 7 : retour arrière AUTOMATIQUE de
#       l'artefact vers la version précédente (la base reste migrée : les
#       migrations sont compatibles avec la version précédente, schéma
#       « expand and contract », DAT 4.2.2). Le retour arrière de la base
#       est une décision humaine : --retour-arriere --base.
#
#  Déploiement par module (DAT 9.3) : --module back ou --module front ne
#  touche que la partie concernée.
#
#  Modules métier (DAT 9.3, T-088) : ocr, workflow, cycledevie, export,
#  notifications, integration. --activer-module / --desactiver-module
#  écrivent GED_MODULES_<CODE>_ACTIF dans /etc/ged/modules.env, redémarrent
#  le service, puis vérifient la sonde, l'état publié par l'application
#  (métrique ged_module_actif) et le test de fumée ; en cas d'échec, l'état
#  précédent est rétabli. --modules affiche l'état effectif.
#  Procédure : DEPLOIEMENT.md § 10.
#
#  Fichiers lus sur le serveur (modèles dans deploiement/) :
#    /etc/ged/ged.env          configuration du service (voir systemd/)
#    /etc/ged/liquibase.env    identifiants de ged_owner (root, 0400)
#    /etc/ged/deploiement.env  URL, compte de fumée (root, 0400)
#    /etc/ged/sauvegarde.env   destination des sauvegardes (root, 0400)
# =====================================================================
set -Eeuo pipefail

DIR_SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=commun.sh
source "$DIR_SCRIPTS/commun.sh"

GED_CONF_DIR="${GED_CONF_DIR:-/etc/ged}"
GED_RACINE="${GED_RACINE:-/opt/ged}"
GED_SERVICE="${GED_SERVICE:-ged-backend}"
GED_ETAT_DIR="${GED_ETAT_DIR:-/var/lib/ged/deploiement}"
GED_JOURNAL="${GED_JOURNAL:-/var/log/ged/deploiements.log}"
DELAI_SANTE_S="${GED_DELAI_SANTE_S:-180}"
CHANGELOG="${GED_LIQUIBASE_CHANGELOG:-db/changelog/db.changelog-master.xml}"
LIQUIBASE_CMD="${LIQUIBASE_CMD:-liquibase}"

usage() {
    sed -n '4,12p' "$0" | sed 's/^#  \{0,1\}//'
    exit 2
}

trap 'journal "Erreur ligne $LINENO : $BASH_COMMAND"' ERR

# ---------------------------------------------------------------------
# Arguments
# ---------------------------------------------------------------------
[[ $# -ge 2 ]] || usage
ENVIRONNEMENT="$1"; shift
case "$ENVIRONNEMENT" in dev|uat|prod) ;; *) usage ;; esac

MODE=deployer; JAR=""; FRONT=""; MODULE=tout; AVEC_BASE=non; RETOUR_AUTO=oui
MODULE_METIER=""; ETAT_MODULE=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --jar) JAR="$2"; shift 2 ;;
        --front) FRONT="$2"; shift 2 ;;
        --module) MODULE="$2"; shift 2 ;;
        --retour-arriere) MODE=retour; shift ;;
        --base) AVEC_BASE=oui; shift ;;
        --verifier) MODE=verifier; shift ;;
        --sans-retour-auto) RETOUR_AUTO=non; shift ;;
        --modules) MODE=modules; shift ;;
        --activer-module) [[ $# -ge 2 ]] || usage; MODE=module; MODULE_METIER="$2"; ETAT_MODULE=true; shift 2 ;;
        --desactiver-module) [[ $# -ge 2 ]] || usage; MODE=module; MODULE_METIER="$2"; ETAT_MODULE=false; shift 2 ;;
        *) usage ;;
    esac
done
case "$MODULE" in back|front|tout) ;; *) usage ;; esac

# ---------------------------------------------------------------------
# Contrôles préalables
# ---------------------------------------------------------------------
[[ "$(id -u)" == "0" || "${GED_SANS_ROOT:-non}" == "oui" ]] || echec "à exécuter en root (sudo)"
mkdir -p "$GED_ETAT_DIR" "$(dirname "$GED_JOURNAL")"

charger_env "$GED_CONF_DIR/ged.env"
charger_env "$GED_CONF_DIR/deploiement.env"

# Garde-fou : on ne déploie pas une configuration PROD en croyant viser l'UAT.
[[ "${GED_ENVIRONNEMENT:-}" == "$ENVIRONNEMENT" ]] \
    || echec "ce serveur est configuré pour « ${GED_ENVIRONNEMENT:-?} », pas pour « $ENVIRONNEMENT »"

exiger_commandes curl jq systemctl sha256sum unzip tar
: "${GED_URL_PUBLIQUE:?GED_URL_PUBLIQUE absente de deploiement.env}"
GED_URL_MANAGEMENT="${GED_URL_MANAGEMENT:-http://${GED_MANAGEMENT_ADRESSE:-127.0.0.1}:${GED_MANAGEMENT_PORT:-8081}}"

HORO="$(horodatage)"
BACK_VERSIONS="$GED_RACINE/backend/versions"
FRONT_VERSIONS="$GED_RACINE/front/versions"
LIEN_JAR="$GED_RACINE/backend/ged.jar"
LIEN_FRONT="$GED_RACINE/front/courant"

# Verrou : deux déploiements simultanés sur le même serveur se corrompraient.
if [[ -d /run/lock && -w /run/lock ]]; then VERROU=/run/lock/ged-deploiement.lock; else VERROU="$GED_ETAT_DIR/.verrou"; fi
exec 9>"$VERROU"
if command -v flock >/dev/null 2>&1; then
    flock -n 9 || echec "un autre déploiement est en cours"
fi

# ---------------------------------------------------------------------
# Étapes
# ---------------------------------------------------------------------
verifier_empreinte() {
    local fichier="$1"
    [[ -f "$fichier" ]] || echec "artefact introuvable : $fichier"
    # L'empreinte publiée par la CI (fichier .sha256 à côté de l'artefact)
    # garantit qu'on déploie exactement ce qui a été construit et testé
    # (DAT 6.2.3 A08).
    if [[ -f "$fichier.sha256" ]]; then
        (cd "$(dirname "$fichier")" && sha256sum -c "$(basename "$fichier").sha256" >/dev/null) \
            || echec "empreinte SHA-256 invalide pour $fichier"
        journal "Empreinte vérifiée : $(cut -d' ' -f1 "$fichier.sha256")"
    elif [[ "$ENVIRONNEMENT" != "dev" ]]; then
        echec "empreinte $fichier.sha256 absente : obligatoire hors DEV"
    fi
}

# Prérequis du stockage chiffré (lot E5), contrôlés AVANT toute action : un
# keystore absent ferait refuser le démarrage après l'arrêt du service, soit
# une coupure au lieu d'un refus de déployer.
verifier_prerequis_stockage() {
    [[ "$ENVIRONNEMENT" == dev ]] && return 0
    local ks="${GED_KEYSTORE_CHEMIN:-}" racine="${GED_STOCKAGE_RACINE:-/var/lib/ged/fichiers}"
    [[ -n "$ks" ]] || echec "GED_KEYSTORE_CHEMIN absent de ged.env"
    [[ -n "${GED_KEYSTORE_MDP:-}" ]] || echec "GED_KEYSTORE_MDP absent de ged.env"
    [[ -f "$ks" ]] || echec "keystore introuvable : $ks"
    case "$(readlink -f "$ks")/" in
        "$(readlink -f "$racine")"/*) echec "le keystore ne doit pas être sous le référentiel de fichiers ($racine)" ;;
    esac
    case "$(stat -c '%U %a' "$ks")" in
        "ged 400"|"ged 600") ;;
        *) echec "keystore $ks : propriétaire ged et permissions 0400 ou 0600 attendus" ;;
    esac
    if command -v findmnt >/dev/null 2>&1; then
        findmnt -t tmpfs /var/lib/ged/tmpfs >/dev/null \
            || echec "/var/lib/ged/tmpfs n'est pas un tmpfs monté : des fichiers en clair iraient sur disque"
    fi
    # Clé de signature des jetons (lot identité) : sans elle, le back-end refuse de démarrer.
    [[ -n "${GED_JWT_KEYSTORE:-}" && -f "${GED_JWT_KEYSTORE}" ]] \
        || echec "GED_JWT_KEYSTORE absent ou introuvable : clé de signature des jetons"
    # Copie hors base des scellements d'audit : répertoire présent, idéalement en ajout seul.
    local export_audit="${GED_AUDIT_EXPORT_SCELLEMENTS:-}"
    [[ -n "$export_audit" && -d "$(dirname "$export_audit")" ]] \
        || echec "GED_AUDIT_EXPORT_SCELLEMENTS : répertoire absent (${export_audit:-non défini})"
    if [[ -f "$export_audit" ]] && command -v lsattr >/dev/null 2>&1 \
            && ! lsattr "$export_audit" 2>/dev/null | cut -d' ' -f1 | grep -q a; then
        journal "ATTENTION : $export_audit n'a pas l'attribut « ajout seul » (chattr +a)"
    fi
    journal "Prérequis : keystores, tmpfs et export des scellements en place"
}

sauvegarde_prealable() {
    journal "Sauvegarde préalable de la base"
    "$DIR_SCRIPTS/../sauvegarde/sauvegarder-base.sh" --logique --etiquette "avant-deploiement-$HORO" \
        || echec "sauvegarde préalable impossible : déploiement interrompu"
}

# Exécute une commande Liquibase avec le compte propriétaire ged_owner et le
# changelog embarqué dans le JAR donné. Les identifiants passent par
# l'environnement du seul processus Liquibase, jamais par la ligne de
# commande (visible dans ps).
liquibase_jar() {
    local jar="$1"; shift
    local extrait
    extrait="$(mktemp -d)"
    unzip -q "$jar" 'BOOT-INF/classes/*' -d "$extrait" \
        || { rm -rf "$extrait"; echec "changelog introuvable dans $jar"; }
    (
        charger_env "$GED_CONF_DIR/liquibase.env"
        # Liaison chiffrée et serveur authentifié (DAT 6.2.1).
        local url="jdbc:postgresql://${DB_HOST}:${DB_PORT:-5432}/${DB_NAME}?sslmode=${DB_SSLMODE:-verify-full}"
        [[ -n "${DB_SSLROOTCERT:-}" ]] && url+="&sslrootcert=${DB_SSLROOTCERT}"
        export LIQUIBASE_COMMAND_URL="$url"
        export LIQUIBASE_COMMAND_USERNAME="${DB_OWNER_USER:-ged_owner}"
        export LIQUIBASE_COMMAND_PASSWORD="${DB_OWNER_PASSWORD:?DB_OWNER_PASSWORD absent de liquibase.env}"
        export LIQUIBASE_COMMAND_CHANGELOG_FILE="$CHANGELOG"
        export LIQUIBASE_COMMAND_DEFAULT_SCHEMA_NAME="${DB_SCHEMA:-ged}"
        export LIQUIBASE_LIQUIBASE_SCHEMA_NAME="${DB_SCHEMA_LIQUIBASE:-ged_liquibase}"
        export LIQUIBASE_SEARCH_PATH="$extrait/BOOT-INF/classes"
        "$LIQUIBASE_CMD" --log-level=WARNING "$@"
    )
    local code=$?
    rm -rf "$extrait"
    return $code
}

verifier_version_liquibase() {
    local jar="$1" embarquee installee
    embarquee="$(unzip -Z1 "$jar" | sed -n 's#^BOOT-INF/lib/liquibase-core-\(.*\)\.jar$#\1#p' | head -1)"
    installee="$("$LIQUIBASE_CMD" --version 2>/dev/null | sed -n 's/^Liquibase Version: *//p' | head -1)"
    [[ -n "$embarquee" ]] || echec "liquibase-core absent du JAR"
    # Même version que l'application : le registre DATABASECHANGELOG et le
    # calcul des sommes de contrôle ne doivent pas diverger.
    if [[ "${embarquee%.*}" != "${installee%.*}" ]]; then
        echec "Liquibase installé ($installee) différent de celui du JAR ($embarquee)"
    fi
}

migrer_base() {
    local jar="$1"
    exiger_commandes "$LIQUIBASE_CMD"
    verifier_version_liquibase "$jar"
    journal "Liquibase : validate"
    liquibase_jar "$jar" validate || echec "changelog invalide : rien n'a été modifié"
    journal "Liquibase : changements en attente"
    liquibase_jar "$jar" status --verbose | tee -a "$GED_JOURNAL" || true
    # Point de retour arrière : l'état de la base AVANT cette migration.
    local tag="deploiement-$HORO"
    if liquibase_jar "$jar" tag --tag="$tag"; then
        echo "$tag" > "$GED_ETAT_DIR/tag-liquibase"
        journal "Liquibase : point de retour « $tag » posé"
    else
        journal "Liquibase : base vierge, aucun point de retour (première installation)"
        rm -f "$GED_ETAT_DIR/tag-liquibase"
    fi
    journal "Liquibase : update"
    liquibase_jar "$jar" update || echec "migration en échec : restaurer la sauvegarde « avant-deploiement-$HORO » (docs/exploitation/RESTAURATION.md)"
}

arreter_service() {
    journal "Arrêt progressif de $GED_SERVICE"
    systemctl stop "$GED_SERVICE"
    ! systemctl is-active --quiet "$GED_SERVICE" || echec "$GED_SERVICE ne s'est pas arrêté"
}

demarrer_service() {
    journal "Démarrage de $GED_SERVICE"
    systemctl start "$GED_SERVICE"
}

# Bascule atomique d'un lien symbolique : jamais d'état intermédiaire où le
# service ou NGINX pointerait vers un chemin inexistant.
basculer_lien() {
    local cible="$1" lien="$2"
    ln -sfn "$cible" "$lien.nouveau"
    mv -Tf "$lien.nouveau" "$lien"
}

installer_back() {
    local jar="$1" dest="$BACK_VERSIONS/$HORO"
    install -d -o root -g ged -m 0750 "$dest"
    install -o root -g ged -m 0640 "$jar" "$dest/ged.jar"
    [[ -f "$jar.sha256" ]] && cp "$jar.sha256" "$dest/ged.jar.sha256"
    [[ -L "$LIEN_JAR" ]] && readlink -f "$LIEN_JAR" > "$GED_ETAT_DIR/back-precedent"
    basculer_lien "$dest/ged.jar" "$LIEN_JAR"
    journal "Back-end installé : $dest/ged.jar"
}

installer_front() {
    local archive="$1" dest="$FRONT_VERSIONS/$HORO"
    install -d -m 0755 "$dest"
    tar -xzf "$archive" -C "$dest"
    [[ -f "$dest/index.html" ]] || echec "paquet front invalide : index.html absent à la racine de l'archive"
    # Sans config.json valide, Angular bascule en démonstration sans prévenir.
    jq -e '.demo == false' "$GED_CONF_DIR/front/config.json" >/dev/null \
        || echec "$GED_CONF_DIR/front/config.json absent ou en mode démonstration"
    [[ -L "$LIEN_FRONT" ]] && readlink -f "$LIEN_FRONT" > "$GED_ETAT_DIR/front-precedent"
    basculer_lien "$dest" "$LIEN_FRONT"
    journal "Front-end installé : $dest"
}

attendre_sante() {
    journal "Attente de la sonde de disponibilité ($DELAI_SANTE_S s max)"
    local fin=$((SECONDS + DELAI_SANTE_S)) etat=""
    while (( SECONDS < fin )); do
        etat="$(curl -fsS --max-time 5 "$GED_URL_MANAGEMENT/actuator/health/readiness" 2>/dev/null | jq -r '.status' 2>/dev/null || true)"
        [[ "$etat" == "UP" ]] && { journal "Sonde de disponibilité : UP"; return 0; }
        sleep 5
    done
    journal "Sonde de disponibilité : ${etat:-sans réponse}"
    curl -sS --max-time 5 "$GED_URL_MANAGEMENT/actuator/health" 2>/dev/null | tee -a "$GED_JOURNAL" || true
    return 1
}

verifications() {
    attendre_sante && "$DIR_SCRIPTS/test-fumee.sh" "$GED_URL_PUBLIQUE"
}

# ---------------------------------------------------------------------
# Modules métier (DAT 9.3, T-088)
# ---------------------------------------------------------------------
MODULES_CONNUS=(ocr workflow cycledevie export notifications integration)
MODULES_ENV="$GED_CONF_DIR/modules.env"

# État effectif publié par l'application : une ligne « code 1 » ou « code 0 » par module.
etat_modules() {
    curl -fsS --max-time 5 "$GED_URL_MANAGEMENT/actuator/prometheus" 2>/dev/null \
        | sed -n 's/^ged_module_actif{.*module="\([a-z]*\)".*} \([0-9.]*\)$/\1 \2/p' \
        | awk '{ printf "%s %d\n", $1, $2 }'
}

changer_module() {
    local code="$1" etat="$2" var attendu obtenu
    [[ " ${MODULES_CONNUS[*]} " == *" $code "* ]] \
        || echec "module inconnu : « $code » (connus : ${MODULES_CONNUS[*]})"
    var="GED_MODULES_${code^^}_ACTIF"
    [[ "$etat" == true ]] && attendu=1 || attendu=0
    journal "=== Module « $code » : actif=$etat ($ENVIRONNEMENT, $HORO) ==="
    [[ -f "$MODULES_ENV" ]] || install -o root -g ged -m 0640 /dev/null "$MODULES_ENV"
    cp -p "$MODULES_ENV" "$GED_ETAT_DIR/modules-precedent.env"
    if grep -q "^$var=" "$MODULES_ENV"; then
        sed -i "s/^$var=.*/$var=$etat/" "$MODULES_ENV"
    else
        echo "$var=$etat" >> "$MODULES_ENV"
    fi
    arreter_service
    demarrer_service
    if attendre_sante; then
        obtenu="$(etat_modules | awk -v m="$code" '$1 == m { print $2 }')"
        if [[ "$obtenu" == "$attendu" ]] && "$DIR_SCRIPTS/test-fumee.sh" "$GED_URL_PUBLIQUE"; then
            journal "Module « $code » : actif=$etat, vérifié"
            etat_modules | tee -a "$GED_JOURNAL"
            return 0
        fi
        journal "Module « $code » : état publié « ${obtenu:-absent} » au lieu de « $attendu », ou test de fumée en échec"
    fi
    journal "Rétablissement de l'état précédent des modules"
    cp -p "$GED_ETAT_DIR/modules-precedent.env" "$MODULES_ENV"
    arreter_service
    demarrer_service
    verifications || echec "état précédent des modules rétabli, vérifications en échec : intervention manuelle"
    echec "changement du module « $code » annulé, état précédent rétabli"
}

retour_arriere() {
    local base="$1" service=non
    journal "=== RETOUR ARRIÈRE ($ENVIRONNEMENT, module $MODULE) ==="
    # Un retour arrière du seul front ne coupe pas le back-end.
    if [[ "$MODULE" != front || "$base" == oui ]]; then
        service=oui
        arreter_service
    fi
    if [[ "$base" == "oui" ]]; then
        [[ -f "$GED_ETAT_DIR/tag-liquibase" ]] || echec "aucun point de retour Liquibase enregistré"
        local tag
        tag="$(cat "$GED_ETAT_DIR/tag-liquibase")"
        # Le rollback s'exécute avec le JAR ACTUEL, le plus récent : c'est lui
        # qui porte la définition des changesets à défaire.
        journal "Liquibase : rollback jusqu'à « $tag »"
        liquibase_jar "$(readlink -f "$LIEN_JAR")" rollback --tag="$tag" \
            || echec "rollback Liquibase en échec : restaurer la sauvegarde préalable (RESTAURATION.md)"
    fi
    if [[ -f "$GED_ETAT_DIR/back-precedent" && ( "$MODULE" == back || "$MODULE" == tout ) ]]; then
        basculer_lien "$(cat "$GED_ETAT_DIR/back-precedent")" "$LIEN_JAR"
        journal "Back-end rétabli : $(cat "$GED_ETAT_DIR/back-precedent")"
    fi
    if [[ -f "$GED_ETAT_DIR/front-precedent" && ( "$MODULE" == front || "$MODULE" == tout ) ]]; then
        basculer_lien "$(cat "$GED_ETAT_DIR/front-precedent")" "$LIEN_FRONT"
        journal "Front-end rétabli : $(cat "$GED_ETAT_DIR/front-precedent")"
    fi
    [[ "$service" == oui ]] && demarrer_service
    verifications || echec "la version précédente ne passe pas les vérifications : intervention manuelle (RESTAURATION.md)"
    journal "=== Retour arrière terminé ==="
}

# ---------------------------------------------------------------------
# Programme principal
# ---------------------------------------------------------------------
case "$MODE" in
    modules)
        etats="$(etat_modules)"
        [[ -n "$etats" ]] || echec "état des modules indisponible (service arrêté ou port de management injoignable)"
        printf '%s\n' "$etats" | tee -a "$GED_JOURNAL"
        exit 0
        ;;
    module)
        changer_module "$MODULE_METIER" "$ETAT_MODULE"
        exit 0
        ;;
    verifier)
        verifications || echec "vérifications en échec"
        journal "Vérifications réussies"
        exit 0
        ;;
    retour)
        retour_arriere "$AVEC_BASE"
        exit 0
        ;;
esac

journal "=== Déploiement $ENVIRONNEMENT, module $MODULE, $HORO ==="
if [[ "$MODULE" != front ]]; then
    [[ -n "$JAR" ]] || echec "--jar obligatoire pour le module back"
    JAR="$(readlink -f "$JAR")"
    verifier_empreinte "$JAR"
fi
if [[ "$MODULE" != back ]]; then
    [[ -n "$FRONT" ]] || echec "--front obligatoire pour le module front"
    FRONT="$(readlink -f "$FRONT")"
    verifier_empreinte "$FRONT"
fi

if [[ "$MODULE" != front ]]; then
    verifier_prerequis_stockage
    sauvegarde_prealable
    migrer_base "$JAR"
    arreter_service
    installer_back "$JAR"
    [[ "$MODULE" == tout ]] && installer_front "$FRONT"
    demarrer_service
else
    # Le front seul ne touche ni la base ni le service : NGINX sert
    # immédiatement le nouveau paquet dès la bascule du lien.
    installer_front "$FRONT"
fi

if verifications; then
    journal "=== Déploiement réussi ==="
    # Conserver les 5 dernières versions pour le retour arrière, purger le reste.
    for d in "$BACK_VERSIONS" "$FRONT_VERSIONS"; do
        [[ -d "$d" ]] && ls -1dt "$d"/*/ 2>/dev/null | tail -n +6 | xargs -r rm -rf
    done
    exit 0
fi

journal "Vérifications post-déploiement en ÉCHEC"
if [[ "$RETOUR_AUTO" == oui ]]; then
    retour_arriere non
    echec "déploiement annulé, version précédente rétablie ; la base reste migrée (voir RESTAURATION.md pour la défaire)"
fi
echec "déploiement en échec, retour arrière automatique désactivé"
