#!/usr/bin/env bash
# =====================================================================
#  Reprise des données — export de l'ANCIENNE base MySQL
# =====================================================================
#  Produit un fichier par table, au format texte de COPY PostgreSQL :
#  colonnes séparées par une tabulation, NULL écrit \N, et les caractères
#  \  tabulation  saut de ligne  retour chariot  échappés en \\ \t \n \r.
#  Ce format est construit par la requête elle-même (mysql --raw) : il ne
#  dépend ni du dialecte d'INSERT de mysqldump (littéraux bit, échappements
#  MySQL) ni des réglages FILE/secure_file_priv du serveur.
#
#  Usage :
#    MYSQL_HOST=... MYSQL_PORT=3306 MYSQL_USER=... MYSQL_PWD=... MYSQL_DATABASE=ged \
#      ./exporter-mysql.sh <dossier-de-sortie>
#
#  MYSQL_PWD est lu directement par le client mysql : le mot de passe
#  n'apparaît pas dans la ligne de commande. Le compte n'a besoin que de SELECT.
#
#  L'ordre et le nombre des colonnes doivent rester ceux de 01_schema_source.sql.
# =====================================================================
set -euo pipefail

SORTIE="${1:?Usage : exporter-mysql.sh <dossier-de-sortie>}"
: "${MYSQL_DATABASE:?MYSQL_DATABASE manquant}"
mkdir -p "$SORTIE"

# Expressions d'export par nature de colonne.
id()   { echo "IFNULL(CAST($1 AS CHAR), '\\\\N')"; }
bool() { echo "IFNULL(CAST($1+0 AS CHAR), '\\\\N')"; }
ts()   { echo "IFNULL(DATE_FORMAT($1, '%Y-%m-%d %H:%i:%s.%f'), '\\\\N')"; }
jour() { echo "IFNULL(DATE_FORMAT($1, '%Y-%m-%d'), '\\\\N')"; }
txt()  { echo "IFNULL(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE($1, CHAR(0), ''), '\\\\', '\\\\\\\\'), CHAR(9), '\\\\t'), CHAR(10), '\\\\n'), CHAR(13), '\\\\r'), '\\\\N')"; }

exporter() {
  local table="$1"; shift
  local colonnes
  colonnes=$(IFS=,; echo "$*")
  echo "  $table"
  mysql --host="${MYSQL_HOST:-localhost}" --port="${MYSQL_PORT:-3306}" --user="${MYSQL_USER:-root}" \
        --default-character-set=utf8mb4 --batch --raw --skip-column-names \
        --init-command="SET SESSION sql_mode = REPLACE(@@sql_mode, 'NO_BACKSLASH_ESCAPES', ''), time_zone = '+00:00'" \
        "$MYSQL_DATABASE" \
        -e "SELECT $colonnes FROM \`$table\` ORDER BY 1" > "$SORTIE/$table.tsv"
}

echo "Export de $MYSQL_DATABASE vers $SORTIE"
exporter employes "$(id id)" "$(txt first_name)" "$(txt last_name)" "$(bool has_user)" "$(ts created_at)" "$(ts updated_at)"
# comptes_utilisateurs n'est PAS exporté : aucune empreinte de mot de passe ne
# doit quitter l'ancienne base (authentification par l'annuaire depuis le lot E2).
exporter workflow_ged "$(id id)" "$(txt name)" "$(bool deleted)" "$(ts created_at)" "$(ts updated_at)"
exporter workflow_ged_steps "$(id id)" "$(id workflow_ged_id)" "$(id employe_id)" "$(txt label)" "$(id step_order)" "$(ts created_at)" "$(ts updated_at)"
exporter work_spaces "$(id id)" "$(txt name)" "$(txt code)" "$(txt description)" "$(txt status)" "$(id employe_id)" "$(id parent_workspace_id)" "$(id workflow_ged_id)" "$(bool deleted)" "$(ts created_at)" "$(ts updated_at)"
exporter access_groups "$(id id)" "$(txt code)" "$(txt name)" "$(bool droit_access)" "$(bool droit_lecture)" "$(bool droit_modifier)" "$(bool droit_uploader)" "$(bool droit_supprimer)" "$(bool droit_deplacer)" "$(bool droit_ajouter_version)" "$(bool droit_verrouiller_deverrouiller)" "$(bool deleted)" "$(ts created_at)" "$(ts updated_at)"
exporter pivot_workspace_groups "$(id access_group_id)" "$(id workspace_id)"
exporter pivot_employe_groups "$(id access_group_id)" "$(id employe_id)"
exporter etiquettes "$(id id)" "$(txt code)" "$(txt tag)" "$(txt couleur)" "$(bool deleted)" "$(ts created_at)" "$(ts updated_at)"
exporter indices "$(id id)" "$(txt code)" "$(txt nom_index)" "$(txt type_champs)" "$(txt valeurs)" "$(txt valeur_par_defaut)" "$(bool obligatoire)" "$(bool indexe_pour_recherche)" "$(bool index_de_groupage)" "$(bool deleted)" "$(ts created_at)" "$(ts updated_at)"
exporter plan_d_indexations "$(id id)" "$(txt code)" "$(txt nom_du_plan)" "$(bool mode_indexation)" "$(bool manuel)" "$(bool majuscule)" "$(txt separateur)" "$(txt charte_nommage)" "$(bool deleted)" "$(ts created_at)" "$(ts updated_at)"
exporter pivot_plan_d_indexation_indices "$(id plan_d_indexation_id)" "$(id index_id)" "$(id position)"
exporter type_de_documents "$(id id)" "$(txt code)" "$(txt type_de_document)" "$(txt description)" "$(id workspace_id)" "$(id plan_d_indexation_id)" "$(txt type_autorise)" "$(id taille_max_mo)" "$(bool deleted)" "$(ts created_at)" "$(ts updated_at)"
exporter documents_file "$(id id)" "$(txt name)" "$(id workspace_id)" "$(id type_document_id)" "$(txt file_name)" "$(txt file_path)" "$(txt extension)" "$(id size_ko)" "$(jour expiration_date)" "$(txt reference)" "$(bool active)" "$(bool is_locked)" "$(id created_by_employe_id)" "$(bool deleted)" "$(ts created_at)" "$(ts updated_at)"
exporter document_versions "$(id id)" "$(id document_id)" "$(txt file_name)" "$(txt file_path)" "$(txt extension)" "$(id size_ko)" "$(txt observation)" "$(bool is_default)" "$(ts created_at)" "$(ts updated_at)"
exporter pivot_document_etiquettes "$(id document_id)" "$(id etiquette_id)"
exporter document_index_values "$(id id)" "$(id document_id)" "$(id index_field_id)" "$(txt valeur)" "$(ts created_at)" "$(ts updated_at)"
exporter workflow_ged_signatures "$(id id)" "$(id document_id)" "$(id employe_id)" "$(txt step_label)" "$(id step_order)" "$(txt status)" "$(ts signed_at)" "$(txt motif)" "$(ts created_at)" "$(ts updated_at)"
echo "Terminé. Fichiers : $(ls "$SORTIE"/*.tsv | wc -l)"
