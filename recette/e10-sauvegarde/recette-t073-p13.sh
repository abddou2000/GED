#!/usr/bin/env bash
# =====================================================================
#  Recette T-073 (DAT 6.5, 6.1.3) et P-13 (DAT 6.5) — vague 8.
#
#  Exercice complet sur le SCHÉMA RÉEL (migré par Liquibase), avec les
#  scripts LIVRÉS de deploiement/sauvegarde, sans adaptation :
#    1. base ged_qa_v8_source préparée (preparer-base.sql) et migrée ;
#    2. jeu synthétique : N documents + cas limites (version non courante,
#       document en corbeille, document archivé avec copie de conservation
#       PDF/A, archive d'export, aperçu en cache), fichiers aa/bb/<uuid>.enc,
#       keystore et secrets factices, texte extrait « sentinelle » ;
#    3. sauvegarde dans l'ordre imposé (contrôle des refus hors ordre),
#       avec activité entre base et fichiers (2 dépôts, 1 fichier perdu) ;
#       GPG : le « serveur » n'a que la clé publique ;
#    4. DESTRUCTION de la base et du référentiel ;
#    5. restauration (base-logique, fichiers, clés) chronométrée ;
#    6. contrôles : empreinte de chaque table, keystore/secrets, fichiers
#       (empreinte de TOUS les fichiers référencés), droits de la base ;
#    7. P-13 : rapprochement (rapport, quarantaine, purge à 7 jours, cas
#       limites, purge sans recontrôle, fichier créé pendant le rapprochement,
#       fichier mal rangé) ;
#    8. rétention (appliquer_retention) sur des répertoires datés simulés.
#
#  Usage : V8_TRAVAIL=<dossier hors dépôt> recette-t073-p13.sh [N]
#  Sorties : lignes RESULTAT|… (recette/lib/commun.sh) ; code 1 si ÉCHEC.
#  N'utilise que des bases ged_qa_v8_* ; ne démarre pas l'application.
# =====================================================================
set -Eeuo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib-v8.sh"

N="${1:-200}"
BASE=ged_qa_v8_source
W="$V8_TRAVAIL/t073"
PROD="$W/prod"                 # « serveur d'application » simulé
RACINE="$PROD/fichiers"         # GED_STOCKAGE_RACINE
CACHE="$PROD/cache-apercu"      # GED_CACHE_APERCU_RACINE (non sauvegardé)
rm -rf "$W"
mkdir -p "$RACINE" "$CACHE" "$PROD/cles" "$PROD/secrets" "$W/sauvegardes" "$W/sauvegardes-cles" "$W/restauration" "$W/sql"
export GED_JOURNAL="$W/journal.log"
: > "$GED_JOURNAL"
T0=$SECONDS

# ---------------------------------------------------------------------
# 1. Base réelle
# ---------------------------------------------------------------------
info "1. Préparation et migration de $BASE"
supprimer_base "$BASE"
supprimer_base ged_qa_v8_restauree
preparer_et_migrer "$BASE"
resultat T073-00 OK "base $BASE préparée et migrée (schéma réel)" "$(q "$BASE" "select count(*) from ged_liquibase.databasechangelog") changesets"

# ---------------------------------------------------------------------
# 2. Jeu synthétique
# ---------------------------------------------------------------------
info "2. Jeu synthétique ($N documents + cas limites)"
nouveau_fichier() {  # racine → crée aa/bb/<uuid>.enc (contenu aléatoire tenant lieu de chiffré), écrit l'uuid
  local r="$1" id; id="$(uuid_aleatoire)"
  mkdir -p "$r/${id:0:2}/${id:2:2}"
  head -c $(( (RANDOM % 48 + 1) * 1024 )) /dev/urandom > "$r/$(chemin_fichier "$id")"
  echo "$id"
}
sha() { sha256sum "$1" | cut -d' ' -f1; }
taille() { stat -c %s "$1"; }
cle_sql() { echo "insert into ged.cle_fichier(id, dek_enveloppee, kek_identifiant) values ('$1', decode(md5(random()::text) || md5(random()::text), 'hex'), 'kek-qa-v8');"; }

E1="$(uuid_aleatoire)"; N1="$(uuid_aleatoire)"; T1="$(uuid_aleatoire)"
SQL="$W/sql/jeu.sql"
{
  echo "begin;"
  echo "insert into ged.employe(id, first_name, last_name) values ('$E1', 'Recette', 'V8');"
  echo "insert into ged.noeud(id, nom, code, status, employe_id, chemin, nature) values ('$N1', 'Espace QA v8', 'QAV8', 'ACTIF', '$E1', '/', 'ESPACE');"
  echo "insert into ged.type_document(id, code, type_de_document, description, noeud_id) values ('$T1', 'QAV8', 'Type QA v8', 'Recette vague 8', '$N1');"
} > "$SQL"
declare -A ROLE     # uuid de fichier → rôle (VERSION, VERSION_NON_COURANTE, CORBEILLE, PDFA, EXPORT)
doc_avec_version() {  # nom [courante] → écrit le SQL, renvoie « doc|version|fichier »
  local nom="$1" d v f; d="$(uuid_aleatoire)"; v="$(uuid_aleatoire)"; f="$(nouveau_fichier "$RACINE")"
  local p="$RACINE/$(chemin_fichier "$f")"
  { cle_sql "$f"
    echo "insert into ged.document(id, name, noeud_principal_id, type_document_id) values ('$d', '$nom', '$N1', '$T1');"
    echo "insert into ged.version_document(id, document_id, file_name, numero, courante, cle_fichier_id, empreinte, taille_octets, type_mime) values ('$v', '$d', '$nom.pdf', 1, true, '$f', '$(sha "$p")', $(taille "$p"), 'application/pdf');"
  } >> "$SQL"
  echo "$d|$v|$f"
}
for i in $(seq 1 "$N"); do
  r="$(doc_avec_version "doc-$i")"; ROLE[${r##*|}]=VERSION
  [[ $i == 1 ]] && PREMIER="$r"
done
# Version non courante : document à deux versions (v1 non courante, v2 courante).
IFS='|' read -r D_MULTI V1_MULTI F1_MULTI <<<"$(doc_avec_version "doc-deux-versions")"
F2_MULTI="$(nouveau_fichier "$RACINE")"; P2="$RACINE/$(chemin_fichier "$F2_MULTI")"
{ cle_sql "$F2_MULTI"
  echo "update ged.version_document set courante = false where id = '$V1_MULTI';"
  echo "insert into ged.version_document(id, document_id, file_name, numero, courante, cle_fichier_id, empreinte, taille_octets, type_mime) values ('$(uuid_aleatoire)', '$D_MULTI', 'doc-deux-versions-v2.pdf', 2, true, '$F2_MULTI', '$(sha "$P2")', $(taille "$P2"), 'application/pdf');"
} >> "$SQL"
ROLE[$F1_MULTI]=VERSION_NON_COURANTE; ROLE[$F2_MULTI]=VERSION
# Document en corbeille.
IFS='|' read -r D_CORB V_CORB F_CORB <<<"$(doc_avec_version "doc-corbeille")"
echo "update ged.document set supprime = true, supprime_par = '$E1', supprime_le = now() where id = '$D_CORB';" >> "$SQL"
ROLE[$F_CORB]=CORBEILLE
# Document archivé avec sa copie de conservation PDF/A (fichier chiffré à part).
IFS='|' read -r D_ARCH V_ARCH F_ARCH <<<"$(doc_avec_version "doc-archive")"
F_PDFA="$(nouveau_fichier "$RACINE")"; PP="$RACINE/$(chemin_fichier "$F_PDFA")"
{ cle_sql "$F_PDFA"
  echo "insert into ged.copie_conservation(id, version_id, cle_fichier_id, empreinte, taille_octets, statut, format) values ('$(uuid_aleatoire)', '$V_ARCH', '$F_PDFA', '$(sha "$PP")', $(taille "$PP"), 'VALIDE', 'PDF/A-2b');"
  echo "update ged.document set statut_conservation = 'ARCHIVE', archive_le = now() where id = '$D_ARCH';"
} >> "$SQL"
ROLE[$F_ARCH]=VERSION_ARCHIVEE; ROLE[$F_PDFA]=PDFA
# Archive d'export terminée (ZIP chiffré dans le même référentiel).
F_EXPORT="$(nouveau_fichier "$RACINE")"; PE="$RACINE/$(chemin_fichier "$F_EXPORT")"
{ cle_sql "$F_EXPORT"
  echo "insert into ged.job_export(id, dossier_id, dossier_nom, demandeur_employe_id, etat, nb_documents, cle_fichier_id, taille_octets, termine_le, expire_le) values ('$(uuid_aleatoire)', '$N1', 'Espace QA v8', '$E1', 'TERMINE', 3, '$F_EXPORT', $(taille "$PE"), now(), now() + interval '7 days');"
} >> "$SQL"
ROLE[$F_EXPORT]=EXPORT
# Aperçu en cache : clé dans cle_fichier, fichier dans le référentiel du CACHE (ConfigurationFichiers).
F_APERCU="$(nouveau_fichier "$CACHE")"
cle_sql "$F_APERCU" >> "$SQL"
# Texte extrait « sentinelle » (colonne en clair en base, DAT 6.1.3).
IFS='|' read -r D1 V1 F1 <<<"$PREMIER"
echo "insert into ged.document_texte(id, document_id, version_id, langue, texte, tsv, provenance) values ('$(uuid_aleatoire)', '$D1', '$V1', 'fra', 'SENTINELLE-QA-V8 contrat confidentiel', to_tsvector('french', 'SENTINELLE-QA-V8 contrat confidentiel'), 'NATIF');" >> "$SQL"
echo "commit;" >> "$SQL"
"$PSQL" -X -q -v ON_ERROR_STOP=1 -d "$BASE" -f "$SQL" >/dev/null
# Keystore, secrets et clé de jetons factices.
head -c 4096 /dev/urandom > "$PROD/cles/ged-kek.p12"
head -c 2048 /dev/urandom > "$PROD/cles/ged-jwt.p12"
printf 'GED_KEYSTORE_MDP=qa-v8-factice\nDB_PASSWORD=qa-v8\n' > "$PROD/secrets/ged.env"
printf 'DB_OWNER_PASSWORD=qa-v8\n' > "$PROD/secrets/liquibase.env"
NB_CLES_INIT="$(q "$BASE" "select count(*) from ged.cle_fichier")"
NB_FICHIERS_INIT="$(find "$RACINE" -name '*.enc' | wc -l)"
resultat T073-01 OK "jeu synthétique en base et sur disque" "cle_fichier=$NB_CLES_INIT ; fichiers référentiel=$NB_FICHIERS_INIT (+1 aperçu dans le cache) ; documents=$(q "$BASE" "select count(*) from ged.document")"

# GPG : le responsable sécurité (clé privée) et le serveur (clé publique seule).
GPG_RSSI="$(mktemp -d /tmp/v8r.XXXX)"; GPG_SRV="$(mktemp -d /tmp/v8s.XXXX)"
chmod 0700 "$GPG_RSSI" "$GPG_SRV"
nettoyer_gpg() { GNUPGHOME="$GPG_RSSI" gpgconf --kill gpg-agent 2>/dev/null || true; GNUPGHOME="$GPG_SRV" gpgconf --kill gpg-agent 2>/dev/null || true; rm -rf "$GPG_RSSI" "$GPG_SRV"; }
trap nettoyer_gpg EXIT
GNUPGHOME="$GPG_RSSI" gpg --batch --quiet --pinentry-mode loopback --passphrase '' \
  --quick-gen-key 'RSSI essai QA v8 <rssi-qa-v8@ged.invalid>' rsa3072 encr never 2>/dev/null
GNUPGHOME="$GPG_RSSI" gpg --batch --export rssi-qa-v8@ged.invalid > "$W/rssi.pub"
GNUPGHOME="$GPG_SRV" gpg --batch --quiet --import "$W/rssi.pub" 2>/dev/null
[[ -z "$(GNUPGHOME="$GPG_SRV" gpg --batch --list-secret-keys 2>/dev/null)" ]] \
  && resultat T073-02 OK "serveur simulé : clé publique GPG seule (aucune clé privée)" \
  || resultat T073-02 ECHEC "le trousseau du serveur contient une clé privée"

cat > "$W/sauvegarde.env" <<ENV
PGHOST=$PGHOST
PGPORT=$PGPORT
PGUSER=postgres
PGDATABASE=$BASE
GED_SAUVEGARDE_DESTINATION=$W/sauvegardes
GED_SAUVEGARDE_CLES_DESTINATION=$W/sauvegardes-cles
GED_SAUVEGARDE_GPG_DESTINATAIRE=rssi-qa-v8@ged.invalid
GED_STOCKAGE_RACINE=$RACINE
GED_KEYSTORE_CHEMIN=$PROD/cles/ged-kek.p12
GED_SAUVEGARDE_SECRETS="$PROD/secrets/ged.env $PROD/secrets/liquibase.env $PROD/cles/ged-jwt.p12 $PROD/secrets/absent.mdp"
GED_SCHEMA=ged
ENV
export GED_SAUVEGARDE_ENV="$W/sauvegarde.env"

# Garde de permissions (0400) : le fichier de configuration en 0644 doit être refusé.
sed "s#^GED_SAUVEGARDE_DESTINATION=.*#GED_SAUVEGARDE_DESTINATION=$W/essai-permissions#" "$W/sauvegarde.env" > "$W/sauvegarde-permissions.env"
chmod 0644 "$W/sauvegarde-permissions.env"
if ( GED_SAUVEGARDE_ENV="$W/sauvegarde-permissions.env" GED_IGNORER_PERMISSIONS=non "$SAUV/sauvegarder-base.sh" --logique ) >"$W/refus-permissions.log" 2>&1; then
  resultat T073-03 AVERT "sauvegarde.env en $(stat -c %a "$W/sauvegarde.env") accepté (NTFS : droits POSIX non significatifs)" "$(tail -1 "$W/refus-permissions.log")"
else
  resultat T073-03 OK "fichier de configuration lisible par tous refusé" "$(grep -o 'doit être en 0400.*' "$W/refus-permissions.log" | head -1)"
fi
export GED_IGNORER_PERMISSIONS=oui

# ---------------------------------------------------------------------
# 3. Sauvegarde dans l'ordre imposé
# ---------------------------------------------------------------------
info "3. Sauvegarde (ordre base → fichiers → clés)"
if GNUPGHOME="$GPG_SRV" "$SAUV/sauvegarder-fichiers.sh" >"$W/refus-ordre-1.log" 2>&1; then
  resultat T073-10 ECHEC "fichiers sauvegardés SANS sauvegarde de base préalable"
else
  resultat T073-10 OK "fichiers avant toute base : refusé" "$(grep -o 'ÉCHEC.*' "$W/refus-ordre-1.log" | head -1)"
fi
t=$SECONDS
"$SAUV/sauvegarder-base.sh" --logique > "$W/sauvegarde-base.log" 2>&1
D_SAUV_BASE=$((SECONDS - t))
SAUV_BASE="$(ls -d "$W"/sauvegardes/base/*/ | head -1)"; SAUV_BASE="${SAUV_BASE%/}"
# État de référence = instant de la sauvegarde de base.
empreintes_tables "$BASE" ged > "$W/ref-tables-ged.txt"
empreintes_tables "$BASE" ged_liquibase > "$W/ref-tables-liquibase.txt"
q "$BASE" "select datacl::text from pg_database where datname = current_database()" > "$W/ref-datacl.txt"
q "$BASE" "select r.rolname || ' ' || array_to_string(s.setconfig, ',') from pg_db_role_setting s join pg_roles r on r.oid = s.setrole join pg_database d on d.oid = s.setdatabase where d.datname = current_database() order by 1" > "$W/ref-roles.txt"
[[ -f "$SAUV_BASE/TERMINE" && -s "$SAUV_BASE/$BASE.dump" && -s "$SAUV_BASE/EMPREINTES" ]] \
  && resultat T073-11 OK "sauvegarde de base terminée, relue et marquée" "$(basename "$SAUV_BASE") en ${D_SAUV_BASE} s ; $(grep -c ' TABLE DATA ' "$SAUV_BASE/$BASE.contenu") tables" \
  || resultat T073-11 ECHEC "sauvegarde de base incomplète"
if "$PG_BIN/pg_restore" -f - "$SAUV_BASE/$BASE.dump" 2>/dev/null | grep -q 'SENTINELLE-QA-V8'; then
  resultat T073-12 AVERT "export logique NON chiffré : le texte extrait des documents y est lisible en clair" "protection = chiffrement du volume de sauvegarde (EXPLOITATION.md §11, P-10) ; le DAT 6.5 n'exige le chiffrement que pour les clés"
else
  resultat T073-12 OK "texte extrait non lisible dans l'export"
fi
if "$PG_BIN/pg_restore" --list "$SAUV_BASE/$BASE.dump" | grep -q 'TABLE DATA ged cle_fichier'; then
  resultat T073-13 AVERT "la table cle_fichier (DEK enveloppées) figure aussi dans l'export de base, sur le même support que les fichiers" "sans la KEK (archive GPG séparée) les DEK restent inutilisables ; lecture littérale du DAT 6.5 « cle_fichier conservée séparément des fichiers » à confirmer"
fi

# Activité entre la base et les fichiers : 2 dépôts postérieurs, 1 fichier perdu.
POST1="$(nouveau_fichier "$RACINE")"; POST2="$(nouveau_fichier "$RACINE")"
{ echo "begin;"; cle_sql "$POST1"; cle_sql "$POST2"; echo "commit;"; } > "$W/sql/post.sql"
"$PSQL" -X -q -v ON_ERROR_STOP=1 -d "$BASE" -f "$W/sql/post.sql" >/dev/null
IFS='|' read -r D_PERDU V_PERDU F_PERDU <<<"$(q "$BASE" "select v.document_id || '|' || v.id || '|' || v.cle_fichier_id from ged.version_document v join ged.document d on d.id = v.document_id where d.name = 'doc-2'")"
rm "$RACINE/$(chemin_fichier "$F_PERDU")"
info "   activité : dépôts $POST1 $POST2 ; fichier perdu $F_PERDU (document doc-2)"

t=$SECONDS
"$SAUV/sauvegarder-fichiers.sh" > "$W/sauvegarde-fichiers.log" 2>&1
D_SAUV_FICHIERS=$((SECONDS - t))
SAUV_FICH="$(ls -d "$W"/sauvegardes/fichiers/*/ | head -1)"; SAUV_FICH="${SAUV_FICH%/}"
[[ "$(cat "$SAUV_FICH/BASE_DE_REFERENCE")" == "$(basename "$SAUV_BASE")" ]] \
  && resultat T073-14 OK "instantané de fichiers rattaché à la sauvegarde de base" "$(basename "$SAUV_FICH") → $(cat "$SAUV_FICH/BASE_DE_REFERENCE") ; $(grep '^fichiers=' "$SAUV_FICH/MANIFESTE") en ${D_SAUV_FICHIERS} s" \
  || resultat T073-14 ECHEC "BASE_DE_REFERENCE incorrecte"
sleep 1
if "$SAUV/sauvegarder-fichiers.sh" >"$W/refus-ordre-2.log" 2>&1; then
  resultat T073-15 ECHEC "seconde sauvegarde de fichiers acceptée sans nouvelle sauvegarde de base"
else
  resultat T073-15 OK "seconde sauvegarde de fichiers sans nouvelle base : refusée" "$(grep -o 'ÉCHEC.*' "$W/refus-ordre-2.log" | head -1)"
fi
grep -q 'rsync' "$W/sauvegarde-fichiers.log" || info "   rsync absent du poste : copie complète (voie de repli), liens physiques --link-dest non éprouvés"

# Clés : refus si même support que les fichiers, refus sans destinataire GPG.
sed "s#^GED_SAUVEGARDE_CLES_DESTINATION=.*#GED_SAUVEGARDE_CLES_DESTINATION=$W/sauvegardes/cles#" "$W/sauvegarde.env" > "$W/sauvegarde-cles-meme-support.env"
if GED_SAUVEGARDE_ENV="$W/sauvegarde-cles-meme-support.env" GNUPGHOME="$GPG_SRV" "$SAUV/sauvegarder-cles.sh" >"$W/refus-cles-1.log" 2>&1; then
  resultat T073-16 ECHEC "clés sauvegardées sur le support des fichiers"
else
  resultat T073-16 OK "clés sur le support des fichiers : refusé" "$(grep -o 'ÉCHEC.*' "$W/refus-cles-1.log" | head -1)"
fi
sed 's/^GED_SAUVEGARDE_GPG_DESTINATAIRE=.*/GED_SAUVEGARDE_GPG_DESTINATAIRE=/' "$W/sauvegarde.env" > "$W/sauvegarde-sans-gpg.env"
if GED_SAUVEGARDE_ENV="$W/sauvegarde-sans-gpg.env" GNUPGHOME="$GPG_SRV" "$SAUV/sauvegarder-cles.sh" >"$W/refus-cles-2.log" 2>&1; then
  resultat T073-17 ECHEC "clés sauvegardées sans destinataire GPG (en clair ?)"
else
  resultat T073-17 OK "sans destinataire GPG : refusé (jamais de clés en clair)" "$(grep -o 'destinataire GPG absent.*' "$W/refus-cles-2.log" | head -1)"
fi
t=$SECONDS
GNUPGHOME="$GPG_SRV" "$SAUV/sauvegarder-cles.sh" > "$W/sauvegarde-cles.log" 2>&1
D_SAUV_CLES=$((SECONDS - t))
NB_CLES_ARCHIVE_REF="$(q "$BASE" "select count(*) from ged.cle_fichier")"
ARCHIVE_CLES="$(ls "$W"/sauvegardes-cles/*-cles.tar.gpg | head -1)"
if tar -tf "$ARCHIVE_CLES" >/dev/null 2>&1 || grep -qa 'GED_KEYSTORE_MDP' "$ARCHIVE_CLES"; then
  resultat T073-18 ECHEC "archive des clés lisible en clair"
else
  resultat T073-18 OK "archive des clés chiffrée GPG, sur un support distinct" "$(basename "$ARCHIVE_CLES") en ${D_SAUV_CLES} s ; $(grep -o '([0-9]* éléments)' "$W/sauvegarde-cles.log")"
fi
if GNUPGHOME="$GPG_SRV" "$SAUV/restaurer.sh" cles --archive "$ARCHIVE_CLES" --cible "$W/restauration/cles-serveur" >"$W/refus-dechiffrement.log" 2>&1; then
  resultat T073-19 ECHEC "le serveur (clé publique seule) a pu relire l'archive des clés"
else
  resultat T073-19 OK "le serveur ne peut pas relire l'archive des clés (clé privée absente)" "$(grep -o 'ÉCHEC.*' "$W/refus-dechiffrement.log" | head -1)"
fi

# ---------------------------------------------------------------------
# 4. Destruction
# ---------------------------------------------------------------------
info "4. Destruction de la base et du référentiel « de production »"
supprimer_base "$BASE"
cp -a "$RACINE" "$W/temoin-fichiers-avant-destruction"   # témoin pour comparaison
KS_SHA="$(sha "$PROD/cles/ged-kek.p12")"; JWT_SHA="$(sha "$PROD/cles/ged-jwt.p12")"
ENV_SHA="$(sha "$PROD/secrets/ged.env")"; LB_SHA="$(sha "$PROD/secrets/liquibase.env")"
rm -rf "$RACINE" "$CACHE" "$PROD/cles" "$PROD/secrets"
base_existe "$BASE" && resultat T073-20 ECHEC "base non détruite" || resultat T073-20 OK "base $BASE, référentiel, cache, keystore et secrets détruits"

# ---------------------------------------------------------------------
# 5. Restauration chronométrée
# ---------------------------------------------------------------------
info "5. Restauration"
DEBUT_R=$SECONDS
t=$SECONDS
"$SAUV/restaurer.sh" base-logique --sauvegarde "$SAUV_BASE" --cible "$BASE" > "$W/restauration-base.log" 2>&1 \
  || fatal "restauration de la base en échec : $(tail -3 "$W/restauration-base.log")"
D_R_BASE=$((SECONDS - t))
if "$SAUV/restaurer.sh" base-logique --sauvegarde "$SAUV_BASE" --cible "$BASE" >"$W/refus-ecrasement.log" 2>&1; then
  resultat T073-21 ECHEC "restauration par-dessus une base existante acceptée"
else
  resultat T073-21 OK "restauration par-dessus une base existante : refusée" "$(grep -o 'ÉCHEC.*' "$W/refus-ecrasement.log" | head -1)"
fi
t=$SECONDS
"$SAUV/restaurer.sh" fichiers --sauvegarde-base "$SAUV_BASE" --cible "$RACINE" > "$W/restauration-fichiers.log" 2>&1 \
  || fatal "restauration des fichiers en échec : $(tail -3 "$W/restauration-fichiers.log")"
D_R_FICH=$((SECONDS - t))
t=$SECONDS
GNUPGHOME="$GPG_RSSI" "$SAUV/restaurer.sh" cles --archive "$ARCHIVE_CLES" --cible "$W/restauration/cles" > "$W/restauration-cles.log" 2>&1 \
  || fatal "restauration des clés en échec : $(tail -3 "$W/restauration-cles.log")"
D_R_CLES=$((SECONDS - t))
# Réinstallation des clés à leur emplacement (étape 4 de RESTAURATION.md).
mkdir -p "$PROD/cles" "$PROD/secrets"
cp "$W/restauration/cles/cles/ged-kek.p12" "$W/restauration/cles/cles/ged-jwt.p12" "$PROD/cles/"
cp "$W/restauration/cles/cles/ged.env" "$W/restauration/cles/cles/liquibase.env" "$PROD/secrets/"
resultat T073-22 OK "restauration base + fichiers + clés" "base ${D_R_BASE} s, fichiers ${D_R_FICH} s, clés ${D_R_CLES} s"

# ---------------------------------------------------------------------
# 6. Contrôles de cohérence
# ---------------------------------------------------------------------
info "6. Contrôles"
empreintes_tables "$BASE" ged > "$W/res-tables-ged.txt"
empreintes_tables "$BASE" ged_liquibase > "$W/res-tables-liquibase.txt"
if diff -q "$W/ref-tables-ged.txt" "$W/res-tables-ged.txt" >/dev/null; then
  resultat T073-30 OK "schéma ged : contenu identique à l'instant de la sauvegarde (toutes tables)" "$(wc -l < "$W/res-tables-ged.txt") tables, $(awk -F'|' '{s+=$2} END {print s}' "$W/res-tables-ged.txt") lignes, md5 par table identiques"
else
  resultat T073-30 ECHEC "écart de contenu après restauration" "$(diff "$W/ref-tables-ged.txt" "$W/res-tables-ged.txt" | head -4 | tr '\n' ' ')"
fi
diff -q "$W/ref-tables-liquibase.txt" "$W/res-tables-liquibase.txt" >/dev/null \
  && resultat T073-31 OK "registre Liquibase identique" \
  || resultat T073-31 ECHEC "registre Liquibase différent"
controle_egal() { [[ "$3" == "$4" ]] && resultat "$1" OK "$2" "$4" || resultat "$1" ECHEC "$2" "attendu $3, obtenu $4"; }
controle_egal T073-32 "cle_fichier restaurée (état de la base sauvegardée)" "$NB_CLES_INIT" "$(q "$BASE" "select count(*) from ged.cle_fichier")"
controle_egal T073-33 "keystore restauré identique (SHA-256)" "$KS_SHA" "$(sha "$PROD/cles/ged-kek.p12")"
controle_egal T073-34 "clé de signature des jetons restaurée identique" "$JWT_SHA" "$(sha "$PROD/cles/ged-jwt.p12")"
controle_egal T073-35 "secrets ged.env / liquibase.env restaurés identiques" "$ENV_SHA/$LB_SHA" "$(sha "$PROD/secrets/ged.env")/$(sha "$PROD/secrets/liquibase.env")"
controle_egal T073-36 "cle_fichier de l'archive des clés = clés à l'instant de la sauvegarde des clés" "$NB_CLES_ARCHIVE_REF" "$(tr -d '\r' < "$W/restauration/cles/cles/cle_fichier.nombre")"
# Contrôle d'intégrité de TOUS les fichiers référencés (et non d'un échantillon).
q "$BASE" "select cle_fichier_id || '|' || empreinte from ged.version_document where cle_fichier_id is not null
           union all select cle_fichier_id || '|' || empreinte from ged.copie_conservation where cle_fichier_id is not null" > "$W/references.txt"
ok=0; ko=0; absents=""
while IFS='|' read -r id emp; do
  f="$RACINE/$(chemin_fichier "$id")"
  if [[ -f "$f" && "$(sha "$f")" == "$emp" ]]; then ok=$((ok + 1)); else ko=$((ko + 1)); absents="$absents $id"; fi
done < "$W/references.txt"
[[ "$ko" == 1 && "$absents" == " $F_PERDU" ]] \
  && resultat T073-37 OK "empreinte de tous les fichiers référencés (versions + copies PDF/A)" "$ok conformes ; 1 absent = le fichier perdu avant la sauvegarde des fichiers ($F_PERDU)" \
  || resultat T073-37 ECHEC "fichiers restaurés non conformes" "$ok conformes, $ko en écart :$absents"
[[ -f "$RACINE/$(chemin_fichier "$F_EXPORT")" && "$(sha "$RACINE/$(chemin_fichier "$F_EXPORT")")" == "$(sha "$W/temoin-fichiers-avant-destruction/$(chemin_fichier "$F_EXPORT")")" ]] \
  && resultat T073-38 OK "archive d'export restaurée identique" \
  || resultat T073-38 ECHEC "archive d'export non restaurée"
# Droits de niveau base : non portés par pg_dump sans --create.
RES_ACL="$(q "$BASE" "select datacl::text from pg_database where datname = current_database()")"
RES_ROLES="$(q "$BASE" "select r.rolname || ' ' || array_to_string(s.setconfig, ',') from pg_db_role_setting s join pg_roles r on r.oid = s.setrole join pg_database d on d.oid = s.setdatabase where d.datname = current_database() order by 1")"
if [[ "$RES_ACL" == "$(cat "$W/ref-datacl.txt")" && "$RES_ROLES" == "$(cat "$W/ref-roles.txt")" ]]; then
  resultat T073-39 OK "droits de connexion et search_path des rôles restaurés"
else
  resultat T073-39 ECHEC "droits de niveau base perdus par la restauration logique" "avant datacl=$(cat "$W/ref-datacl.txt") roles=[$(tr '\n' ';' < "$W/ref-roles.txt")] ; après datacl=${RES_ACL:-NULL (CONNECT/TEMP à PUBLIC)} roles=[${RES_ROLES}]"
fi
# Le correctif documentable : rejouer preparer-base.sql (idempotent) sur la base restaurée.
"$PSQL" -X -q -U postgres -d postgres -v base="$BASE" -f "$DEPOT/backend/scripts/db/preparer-base.sql" >/dev/null
[[ "$(q "$BASE" "select datacl::text from pg_database where datname = current_database()")" == "$(cat "$W/ref-datacl.txt")" ]] \
  && info "   rejouer preparer-base.sql sur la base restaurée rétablit datacl et search_path (correctif documentaire)"

# ---------------------------------------------------------------------
# 7. P-13 — rapprochement
# ---------------------------------------------------------------------
info "7. Rapprochement (P-13)"
RAPP="$SAUV/rapprocher-orphelins.sh"
QUAR="$PROD/quarantaine-orphelins"
avant_rapp="$(empreintes_tables "$BASE" ged | md5sum | cut -d' ' -f1)"
nb_avant="$(find "$RACINE" -name '*.enc' | wc -l)"
t=$SECONDS
( cd "$W" && "$RAPP" --base "$BASE" --racine "$RACINE" --rapport "$W/rapport-seul.txt" ) > "$W/rapp-1.log" 2>&1
D_RAPP=$((SECONDS - t))
[[ "$(find "$RACINE" -name '*.enc' | wc -l)" == "$nb_avant" && ! -d "$QUAR" ]] \
  && resultat P13-01 OK "sans --appliquer : rapport seul, aucun fichier déplacé" "$(grep -E '^(Orphelins|Manquants)' "$W/rapport-seul.txt" | tr -s ' ' | tr '\n' ';')" \
  || resultat P13-01 ECHEC "des fichiers ont bougé sans --appliquer"
( cd "$W" && "$RAPP" --base "$BASE" --racine "$RACINE" --appliquer --rapport "$W/rapport-applique.txt" ) > "$W/rapp-2.log" 2>&1
LOT="$(ls -d "$QUAR"/*/ | head -1)"; LOT="${LOT%/}"
orph="$( (grep '^ORPHELIN ' "$W/rapport-applique.txt" || true) | sed 's#.*/##; s/\.enc$//' | sort | tr '\n' ' ')"
attendu_orph="$(printf '%s\n%s\n' "$POST1" "$POST2" | sort | tr '\n' ' ')"
if [[ "$orph" == "$attendu_orph" && -f "$LOT/$(chemin_fichier "$POST1")" && -f "$LOT/$(chemin_fichier "$POST2")" \
      && ! -f "$RACINE/$(chemin_fichier "$POST1")" ]]; then
  resultat P13-02 OK "orphelins (déposés après la sauvegarde de base) identifiés et mis en quarantaine" "2 fichiers → $(basename "$QUAR")/$(basename "$LOT")"
else
  resultat P13-02 ECHEC "orphelins mal traités" "obtenu [$orph] attendu [$attendu_orph]"
fi
grep -q "^A_REIMPORTER $F_PERDU$" "$W/rapport-applique.txt" \
  && resultat P13-03 OK "fichier manquant signalé A_REIMPORTER dans le rapport" "A_REIMPORTER $F_PERDU (document doc-2 = $D_PERDU)" \
  || resultat P13-03 ECHEC "fichier manquant non signalé"
if grep -q "^A_REIMPORTER $F_APERCU$" "$W/rapport-applique.txt"; then
  resultat P13-04 ECHEC "faux positif : l'aperçu en cache (référentiel distinct, non sauvegardé) est signalé « à ré-importer »" "A_REIMPORTER $F_APERCU ; la requête par défaut (toute la table cle_fichier) inclut les clés du cache d'aperçus (ConfigurationFichiers : même DepotClesFichier)"
else
  resultat P13-04 OK "aucun faux positif lié au cache d'aperçus"
fi
[[ "$(grep -c '^A_REIMPORTER ' "$W/rapport-applique.txt")" -ge 1 ]] && \
  resultat P13-05 AVERT "le rapport donne l'identifiant de FICHIER (cle_fichier), ni le document, ni son nom, ni sa nature (version, copie PDF/A, export, aperçu)" "l'administrateur fonctionnel doit retrouver $D_PERDU par une requête SQL"
apres_rapp="$(empreintes_tables "$BASE" ged | md5sum | cut -d' ' -f1)"
q "$BASE" "select count(*) from information_schema.columns where table_schema = 'ged' and (column_name ilike '%reimport%')" > "$W/col-reimport.txt"
[[ "$avant_rapp" == "$apres_rapp" ]] \
  && resultat P13-06 AVERT "aucun signalement « à ré-importer » en base : la base est inchangée (conforme au script, « rien n'est modifié en base »)" "le DAT dit « signalé par le contrôle d'intégrité » : le contrôle applicatif (VerificationIntegrite) produit ABSENT + événement d'audit INTEGRITE_ANOMALIE, sans statut persistant ; colonnes *reimport* en base : $(cat "$W/col-reimport.txt")" \
  || resultat P13-06 ECHEC "le rapprochement a modifié la base"
# Aucun fichier référencé ne doit avoir bougé (cas limites compris).
intacts=0; manque=""
for f in "${!ROLE[@]}"; do
  [[ "$f" == "$F_PERDU" ]] && continue
  if [[ -f "$RACINE/$(chemin_fichier "$f")" ]]; then intacts=$((intacts + 1)); else manque="$manque ${ROLE[$f]}:$f"; fi
done
[[ -z "$manque" ]] \
  && resultat P13-07 OK "aucun fichier référencé déplacé" "$intacts fichiers intacts dont version non courante ($F1_MULTI), corbeille ($F_CORB), version archivée ($F_ARCH), copie PDF/A ($F_PDFA), archive d'export ($F_EXPORT)" \
  || resultat P13-07 ECHEC "fichiers référencés déplacés :$manque"
# Réexécution : idempotente.
( cd "$W" && "$RAPP" --base "$BASE" --racine "$RACINE" --appliquer --rapport "$W/rapport-2e.txt" ) > "$W/rapp-3.log" 2>&1
[[ "$(grep -c '^ORPHELIN ' "$W/rapport-2e.txt")" == 0 ]] && resultat P13-08 OK "seconde exécution : plus aucun orphelin (idempotent)"

# Purge à 7 jours : l'âge est celui du lot de quarantaine.
touch -d "7 days ago 12 hours ago" "$LOT"
( cd "$W" && "$RAPP" --base "$BASE" --racine "$RACINE" --purger-apres-jours 7 --rapport "$W/rapport-purge-1.txt" ) > "$W/rapp-4.log" 2>&1
[[ -d "$LOT" ]] && P75=conserve || P75=supprime
touch -d "8 days ago 1 hour ago" "$LOT"
( cd "$W" && "$RAPP" --base "$BASE" --racine "$RACINE" --purger-apres-jours 7 --rapport "$W/rapport-purge-2.txt" ) > "$W/rapp-5.log" 2>&1
[[ -d "$LOT" ]] && P8=conserve || P8=supprime
if [[ "$P8" == supprime ]]; then
  resultat P13-09 OK "lot de quarantaine supprimé une fois l'âge dépassé" "7 j 12 h : $P75 ; 8 j 1 h : $P8 (find -mtime +7 : suppression effective à partir de 8 jours pleins)"
else
  resultat P13-09 ECHEC "lot de quarantaine non purgé après 8 jours"
fi
# Purge sans recontrôle en base : un fichier redevenu référencé est détruit.
REVENU="$(nouveau_fichier "$RACINE")"
( cd "$W" && "$RAPP" --base "$BASE" --racine "$RACINE" --appliquer --rapport "$W/rapport-revenu.txt" ) > "$W/rapp-6.log" 2>&1
LOT2="$(ls -d "$QUAR"/*/ | head -1)"; LOT2="${LOT2%/}"
{ echo "begin;"; cle_sql "$REVENU"; echo "commit;"; } | "$PSQL" -X -q -v ON_ERROR_STOP=1 -d "$BASE" >/dev/null
touch -d "9 days ago" "$LOT2"
( cd "$W" && "$RAPP" --base "$BASE" --racine "$RACINE" --purger-apres-jours 7 --rapport "$W/rapport-revenu-2.txt" ) > "$W/rapp-7.log" 2>&1
if [[ "$(q "$BASE" "select count(*) from ged.cle_fichier where id = '$REVENU'")" == 1 && ! -e "$LOT2/$(chemin_fichier "$REVENU")" && ! -e "$RACINE/$(chemin_fichier "$REVENU")" ]]; then
  resultat P13-10 ECHEC "la purge détruit un fichier de quarantaine redevenu RÉFÉRENCÉ en base (aucun recontrôle avant suppression)" "fichier $REVENU : ligne cle_fichier présente, fichier détruit ; $(grep -c 'A_REIMPORTER' "$W/rapport-revenu-2.txt") ligne(s) A_REIMPORTER au rapport suivant"
else
  resultat P13-10 OK "fichier redevenu référencé épargné par la purge"
fi
# Fichier créé PENDANT le rapprochement (application en marche) : publication
# du fichier puis insertion de sa clé dans une transaction encore ouverte
# (StockageChiffre.ecrire : store.ecrire puis cles.enregistrer).
ENCOURS="$(nouveau_fichier "$RACINE")"
( { echo "begin;"; cle_sql "$ENCOURS"; echo "select pg_sleep(12);"; echo "commit;"; } | "$PSQL" -X -q -v ON_ERROR_STOP=1 -d "$BASE" >/dev/null ) &
PID_TX=$!
sleep 3
( cd "$W" && "$RAPP" --base "$BASE" --racine "$RACINE" --appliquer --rapport "$W/rapport-concurrence.txt" ) > "$W/rapp-8.log" 2>&1
wait "$PID_TX"
if [[ "$(q "$BASE" "select count(*) from ged.cle_fichier where id = '$ENCOURS'")" == 1 && ! -f "$RACINE/$(chemin_fichier "$ENCOURS")" ]]; then
  resultat P13-11 AVERT "fichier en cours de dépôt (clé non encore validée) mis en quarantaine si l'application tourne" "fichier $ENCOURS référencé après validation mais déplacé ; le script ne vérifie pas que ged-backend est arrêté (prérequis écrit seulement dans RESTAURATION.md §4) et n'exclut pas les fichiers récents ; purge 7 jours plus tard = perte"
else
  resultat P13-11 OK "fichier en cours de dépôt épargné"
fi
# Fichier .enc mal rangé (chemin ≠ aa/bb/<uuid>) : robustesse.
mkdir -p "$RACINE/zz/zz"
MAL="$(uuid_aleatoire)"; head -c 1024 /dev/urandom > "$RACINE/zz/zz/$MAL.enc"
set +e
( cd "$W" && "$RAPP" --base "$BASE" --racine "$RACINE" --appliquer --rapport "$W/rapport-malrange.txt" ) > "$W/rapp-9.log" 2>&1
code=$?
set -e
if [[ $code -ne 0 ]]; then
  resultat P13-12 AVERT "un .enc mal rangé fait échouer --appliquer (mv vers aa/bb recalculé), sans message explicite" "code $code ; $(grep -a -m1 -o 'mv: .*' "$W/rapp-9.log" || tail -1 "$W/rapp-9.log")"
else
  resultat P13-12 OK "fichier mal rangé traité" "code $code"
fi
rm -rf "$RACINE/zz"

# ---------------------------------------------------------------------
# 8. Rétention (appliquer_retention) sur des répertoires datés simulés
# ---------------------------------------------------------------------
info "8. Rétention"
RET="$W/retention"; mkdir -p "$RET"
for j in $(seq 0 400); do
  d="$(date -d "-$j days" '+%Y%m%d')"
  mkdir -p "$RET/$d-013000"; touch "$RET/$d-013000/TERMINE"
done
( source "$SAUV/commun-sauvegarde.sh"; appliquer_retention "$RET" ) > "$W/retention.log" 2>&1
restants="$(ls -1 "$RET" | wc -l)"
recents="$(ls -1 "$RET" | awk -v l="$(date -d '-30 days' '+%Y%m%d')" '$0 >= l' | wc -l)"
mensuels="$(ls -1 "$RET" | awk -v l="$(date -d '-30 days' '+%Y%m%d')" '$0 < l' | tr '\n' ' ')"
nb_mensuels="$(ls -1 "$RET" | awk -v l="$(date -d '-30 days' '+%Y%m%d')" '$0 < l' | wc -l)"
# Attendu : parmi les sauvegardes de plus de 30 jours et de moins de 12 mois, la plus ancienne de chaque mois.
attendus="$(for j in $(seq 31 400); do date -d "-$j days" '+%Y%m%d'; done | sort \
  | awk -v m="$(date -d '-12 months' '+%Y%m%d')" '$0 > m { mo = substr($0, 1, 6); if (!(mo in vu)) { vu[mo] = 1; print $0 "-013000" } }' | tr '\n' ' ')"
if [[ "$recents" == 31 && "$mensuels" == "$attendus" ]]; then
  resultat T073-40 OK "rétention : 30 jours en ligne + la 1re sauvegarde de chaque mois sur 12 mois" "$restants conservées sur 401 : $recents récentes + $nb_mensuels mensuelles ($mensuels)"
else
  resultat T073-40 ECHEC "rétention non conforme" "$restants conservées ; récentes=$recents ; mensuelles=$mensuels"
fi

# ---------------------------------------------------------------------
# Bilan et durées
# ---------------------------------------------------------------------
D_RESTAURATION=$(( D_R_BASE + D_R_FICH + D_R_CLES + D_RAPP ))
info "Durées : sauvegarde base ${D_SAUV_BASE} s, fichiers ${D_SAUV_FICHIERS} s, clés ${D_SAUV_CLES} s ; restauration base ${D_R_BASE} s, fichiers ${D_R_FICH} s, clés ${D_R_CLES} s, rapprochement ${D_RAPP} s ; total restauration ${D_RESTAURATION} s (RTO 28 800 s) ; exercice $((SECONDS - T0)) s"
resultat T073-41 OK "RTO mesuré sur ce volume" "${D_RESTAURATION} s pour $NB_CLES_INIT fichiers (RTO DAT 8 h) — non représentatif du volume de production"
bilan "recette-t073-p13"
