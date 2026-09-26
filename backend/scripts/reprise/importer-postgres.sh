#!/usr/bin/env bash
# =====================================================================
#  Reprise des données — chargement dans PostgreSQL et transfert en UUID
# =====================================================================
#  Enchaîne, sur la base PostgreSQL cible :
#    1. 01_schema_source.sql   schéma de transit `reprise_source` (recréé vide) ;
#    2. chargement des fichiers .tsv produits par exporter-mysql.sh ;
#    3. 02_reprise.sql         transfert vers le schéma cible, en UNE transaction ;
#    4. 03_controles.sql       contrôles ; code de sortie 2 si un écart subsiste ;
#    5. export de la table de correspondance ancien id -> UUID (CSV), à archiver.
#
#  Prérequis : rôles et base préparés (scripts/db/creer-roles.sql puis
#  preparer-base.sql), schéma cible créé par Liquibase (premier démarrage de
#  l'application ou `liquibase update`) et VIDE — l'application ne doit pas
#  avoir été utilisée entre-temps.
#
#  Usage (compte propriétaire ged_owner) :
#    PGHOST=... PGPORT=5432 PGDATABASE=ged PGUSER=ged_owner PGPASSWORD=... \
#      ./importer-postgres.sh <dossier-des-tsv> [schéma-cible, défaut ged]
# =====================================================================
set -euo pipefail

ENTREE="${1:?Usage : importer-postgres.sh <dossier-des-tsv> [schema-cible]}"
CIBLE="${2:-ged}"
ICI="$(cd "$(dirname "$0")" && pwd)"
PSQL="${PSQL:-psql}"

TABLES=(employes workflow_ged workflow_ged_steps work_spaces access_groups
        pivot_workspace_groups pivot_employe_groups etiquettes indices plan_d_indexations
        pivot_plan_d_indexation_indices type_de_documents documents_file document_versions
        pivot_document_etiquettes document_index_values workflow_ged_signatures)

echo "1. Schéma de transit reprise_source"
"$PSQL" -X -q -v ON_ERROR_STOP=1 -f "$ICI/01_schema_source.sql"

echo "2. Chargement des exports"
for t in "${TABLES[@]}"; do
  f="$ENTREE/$t.tsv"
  if [[ ! -f "$f" ]]; then
    echo "   fichier manquant : $f" >&2
    exit 1
  fi
  "$PSQL" -X -q -v ON_ERROR_STOP=1 \
    -c "\\copy reprise_source.$t FROM '$f' WITH (FORMAT text, ENCODING 'UTF8')"
  echo "   $t : $("$PSQL" -X -A -t -c "SELECT count(*) FROM reprise_source.$t") ligne(s)"
done

echo "3. Transfert vers le schéma $CIBLE (une seule transaction)"
PGOPTIONS="-c search_path=$CIBLE" "$PSQL" -X -q -1 -v ON_ERROR_STOP=1 -f "$ICI/02_reprise.sql"

echo "4. Contrôles"
PGOPTIONS="-c search_path=$CIBLE" "$PSQL" -X -v ON_ERROR_STOP=1 -f "$ICI/03_controles.sql" | tee "$ENTREE/controles.txt"

echo "5. Table de correspondance -> $ENTREE/correspondance.csv"
"$PSQL" -X -q -v ON_ERROR_STOP=1 \
  -c "\\copy (SELECT table_source, ancien_id, id FROM reprise_source.correspondance ORDER BY 1, 2) TO '$ENTREE/correspondance.csv' WITH (FORMAT csv, HEADER true)"

ECARTS=$(PGOPTIONS="-c search_path=$CIBLE" "$PSQL" -X -A -t -v ON_ERROR_STOP=1 -f "$ICI/03_controles.sql" | grep -c '|ECART$' || true)
if [[ "$ECARTS" != "0" ]]; then
  echo "ATTENTION : $ECARTS contrôle(s) en écart. Voir $ENTREE/controles.txt." >&2
  exit 2
fi
echo "Reprise terminée, tous les contrôles sont OK."
echo "Après archivage de correspondance.csv : DROP SCHEMA reprise_source CASCADE;"
