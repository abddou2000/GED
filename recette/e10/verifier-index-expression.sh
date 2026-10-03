#!/usr/bin/env bash
# Recette E10 — T-104 (DAT 12.7) : index d'expression des métadonnées date et nombre.
#
# DEPLOIEMENT.md §8 : « Index d'expression d'une métadonnée fréquente : un changeset par champ, sur les
# fonctions immuables de la base (ged.meta_date, ged.meta_nombre) ; la recherche
# (POST /api/v1/documents/recherche) emploie ces mêmes expressions : l'index sert sans autre changement. »
# On l'éprouve sur une COPIE PEUPLÉE d'une base GED (instance PostgreSQL jetable) :
#
#   I01 meta_date / meta_nombre / meta_texte immuables (provolatile = 'i') : indexables ;
#   I02 N documents clonés avec deux métadonnées de recette (date, montant) dont des valeurs mal formées ;
#       création des deux index d'expression tels que DEPLOIEMENT.md §8 les écrit : aucune erreur ;
#   I03 requête avec constantes : index d'expression employé (date, nombre) ;
#   I04 requête telle que l'application l'envoie (RechercheMetadonnees : code et bornes en PARAMÈTRES liés,
#       PREPARE / EXECUTE comme le pilote JDBC) : index employé au premier EXECUTE ;
#   I05 même requête préparée après 8 exécutions (cache de plans en mode auto) : index toujours employé ;
#   I06 plan générique forcé (plan_cache_mode = force_generic_plan) : l'index ne peut plus servir (code en
#       paramètre) — limite documentée, sans effet en mode auto ;
#   I07 résultats identiques avec et sans index ; valeurs mal formées jamais retenues ; gain mesuré ;
#   I08 critère date lu par un plan PARALLÈLE (fonds assez grand, pas d'index) : la requête doit aboutir.
#   I09 (tour 4, ANO-E7-007) plan parallèle FORCÉ (parallel_setup_cost = 0, parallel_tuple_cost = 0,
#       min_parallel_table_scan_size = 0, max_parallel_workers_per_gather = 2), avec et sans index : date en plage
#       large, étroite, « renseignée », nombre ; une ligne « 1e1000000 » / « 0000-01-01 » ajoutée : mêmes comptes
#       qu'en série, aucune erreur ;
#   I10 (tour 4) sémantique inchangée : meta_date et meta_nombre comparées, en série, aux corps d'origine
#       (202609301020-1) sur toutes les dates AAAA-MM-JJ de 11 années limites (mois 00 à 13, jours 00 à 32) et des
#       nombres limites ; seule différence admise : NULL là où l'ancien corps levait une erreur (débordement).
#
# Usage : verifier-index-expression.sh BASE [N]   (variables libpq : PGHOST, PGPORT, PGUSER ; base jetable :
#         le nom doit contenir « qa » ou « recette ») — les documents clonés et les index restent dans la copie.

source "$(dirname "${BASH_SOURCE[0]}")/../lib/commun.sh"
trouver_psql
B="${1:?base (copie jetable)}"; N="${2:-100000}"
refuser_production "$B"
[[ "$B" =~ qa|recette ]] || fatal "base « $B » : copie jetable attendue (nom contenant qa ou recette)"
q() { pg -At -F '|' -d "$B" -c "SET search_path = ged, public" "$@" 2>&1; }

# --- I01 -----------------------------------------------------------------------------------------
v=$(q -c "SELECT string_agg(proname || '=' || provolatile::text || '/' || proparallel::text, ' ' ORDER BY proname) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE nspname = 'ged' AND proname IN ('meta_date','meta_nombre','meta_texte')")
[[ "$v" == "meta_date=i/"?" meta_nombre=i/"?" meta_texte=i/"? ]] && resultat I01 OK "Fonctions meta_date, meta_nombre, meta_texte immuables : indexables [12.7]" "$v" \
  || resultat I01 ECHEC "Fonctions de lecture typée immuables" "$v"

# --- I02 jeu de données et index ----------------------------------------------------------------
q -c "DROP INDEX IF EXISTS idx_document_meta_date_qa_t104; DROP INDEX IF EXISTS idx_document_meta_montant_qa_t104;" \
  -c "DELETE FROM document WHERE metadonnees ? 'QA_T104_DATE'" > /dev/null
t0=$(date +%s)
q -c "INSERT INTO document (id, name, noeud_principal_id, type_document_id, size_ko, metadonnees, confidentialite, date_document, statut_indexation, created_at)
      SELECT gen_random_uuid(), 'qa-t104-' || g, d.noeud_principal_id, d.type_document_id, 1,
             jsonb_build_object('QA_T104_DATE',
                 CASE WHEN g % 997 = 0 THEN '31/12/2026' WHEN g % 991 = 0 THEN '2026-02-30'
                      ELSE to_char(date '2020-01-01' + (g % 2557), 'YYYY-MM-DD') END,
               'QA_T104_MONTANT', CASE WHEN g % 983 = 0 THEN '12 500,00' ELSE ((g * 37) % 1000000)::text || '.50' END),
             'PUBLIC', CURRENT_DATE, 'INDEXE', now()
      FROM generate_series(1, $N) g, (SELECT noeud_principal_id, type_document_id FROM document WHERE NOT supprime ORDER BY created_at LIMIT 1) d" > /dev/null \
  || fatal "insertion des documents de recette"
nb=$(q -c "SELECT count(*) FROM document WHERE metadonnees ? 'QA_T104_DATE'")
IDX_D="CREATE INDEX idx_document_meta_date_qa_t104 ON ged.document (ged.meta_date(metadonnees, 'QA_T104_DATE'))"
IDX_M="CREATE INDEX idx_document_meta_montant_qa_t104 ON ged.document (ged.meta_nombre(metadonnees, 'QA_T104_MONTANT'))"
err=$(q -c "$IDX_D" -c "$IDX_M")
if [[ -z "$err" ]]; then
  resultat I02 OK "$nb documents clonés (dont valeurs mal formées « 31/12/2026 », « 2026-02-30 », « 12 500,00 ») ; index d'expression créés comme DEPLOIEMENT.md §8 les écrit" "$(( $(date +%s) - t0 )) s"
else
  resultat I02 ECHEC "$nb documents : l'index d'expression de DEPLOIEMENT.md §8 ne se crée pas (construction parallèle de l'index)" "$(tr '\n' ' ' <<<"$err" | cut -c1-260)"
  # Suite des contrôles : construction sans parallélisme (contournement d'exploitation, pas une correction).
  q -c "DROP INDEX IF EXISTS idx_document_meta_date_qa_t104" -c "DROP INDEX IF EXISTS idx_document_meta_montant_qa_t104" > /dev/null
  err2=$(q -c "SET max_parallel_maintenance_workers = 0" -c "$IDX_D" -c "$IDX_M")
  info "index reconstruits avec max_parallel_maintenance_workers = 0 : ${err2:-sans erreur}"
fi
q -c "ANALYZE document" > /dev/null

# --- I08 critère date dans une requête parallèle (forme de RechercheMetadonnees pour l'Administrateur) ----
# Sans index utilisable (index désactivés pour la requête), un fonds de cette taille est lu en parallèle.
APP_D="SELECT count(*) FROM document d WHERE EXISTS (SELECT 1 FROM document droits_d WHERE droits_d.id = d.id AND droits_d.supprime = false) AND meta_date(d.metadonnees, 'QA_T104_DATE') >= DATE '2023-03-01'"
par=$(q -c "SET enable_indexscan = off" -c "SET enable_bitmapscan = off" -c "EXPLAIN $APP_D" | grep -c 'Parallel')
r8=$(q -c "SET enable_indexscan = off" -c "SET enable_bitmapscan = off" -c "$APP_D")
if [[ "$r8" =~ ^[0-9]+$ ]]; then
  resultat I08 OK "Critère date lu par un plan parallèle (forme de RechercheMetadonnees) : la requête aboutit" "plan parallèle : $par ; compte $r8"
else
  resultat I08 ECHEC "Critère date dans un plan parallèle : la requête échoue (meta_date déclarée PARALLEL SAFE mais son bloc EXCEPTION ouvre une sous-transaction, interdite dans un worker) [12.7]" "plan parallèle : $par ; $(tr '\n' ' ' <<<"$r8" | cut -c1-200)"
fi

plan() { q -c "$1" | tr '\n' ' '; }
emploie() { grep -q "$1" <<<"$2"; }
DATE_C="SELECT count(*) FROM document d WHERE NOT d.supprime AND meta_date(d.metadonnees, 'QA_T104_DATE') >= DATE '2023-03-01' AND meta_date(d.metadonnees, 'QA_T104_DATE') <= DATE '2023-03-10'"
MONT_C="SELECT count(*) FROM document d WHERE NOT d.supprime AND meta_nombre(d.metadonnees, 'QA_T104_MONTANT') >= 1000 AND meta_nombre(d.metadonnees, 'QA_T104_MONTANT') <= 1500"

# --- I03 constantes --------------------------------------------------------------------------------
pd=$(plan "EXPLAIN $DATE_C"); pm=$(plan "EXPLAIN $MONT_C")
emploie idx_document_meta_date_qa_t104 "$pd" && emploie idx_document_meta_montant_qa_t104 "$pm" \
  && resultat I03 OK "Critère date et critère nombre (constantes) : index d'expression employés [12.7]" "$(grep -o '[A-Za-z ]*Index Scan on idx_document_meta_[a-z_0-9]*' <<<"$pd $pm" | tr '\n' ';')" \
  || resultat I03 ECHEC "Index d'expression non employés (constantes)" "date : $(cut -c1-200 <<<"$pd") | nombre : $(cut -c1-200 <<<"$pm")"

# --- I04 à I06 paramètres liés, comme RechercheMetadonnees (code :c0 et bornes :c0de, :c0a) --------
PREP_D="PREPARE rd(text, date, date) AS SELECT count(*) FROM document d WHERE NOT d.supprime AND meta_date(d.metadonnees, \$1) >= \$2 AND meta_date(d.metadonnees, \$1) <= \$3"
PREP_M="PREPARE rm(text, numeric, numeric) AS SELECT count(*) FROM document d WHERE NOT d.supprime AND meta_nombre(d.metadonnees, \$1) >= \$2 AND meta_nombre(d.metadonnees, \$1) <= \$3"
ED="EXECUTE rd('QA_T104_DATE', '2023-03-01', '2023-03-10')"; EM="EXECUTE rm('QA_T104_MONTANT', 1000, 1500)"
p1=$(plan "$PREP_D; $PREP_M; EXPLAIN $ED; EXPLAIN $EM")
emploie idx_document_meta_date_qa_t104 "$p1" && emploie idx_document_meta_montant_qa_t104 "$p1" \
  && resultat I04 OK "Requête préparée (code et bornes en paramètres, comme l'application) : index employés au premier EXECUTE" "" \
  || resultat I04 ECHEC "Requête préparée : index non employés" "$(cut -c1-300 <<<"$p1")"
rep=""; for i in 1 2 3 4 5 6 7 8; do rep="$rep $ED; $EM;"; done
p2=$(q -c "$PREP_D" -c "$PREP_M" -c "$rep EXPLAIN $ED; EXPLAIN $EM" | tr '\n' ' ')
emploie idx_document_meta_date_qa_t104 "$p2" && emploie idx_document_meta_montant_qa_t104 "$p2" \
  && resultat I05 OK "Après 8 exécutions de la requête préparée (plan_cache_mode auto) : index toujours employés" "" \
  || resultat I05 AVERT "Après 8 exécutions, le plan générique a remplacé le plan personnalisé : index non employés" "$(cut -c1-300 <<<"$p2")"
p3=$(q -c "SET plan_cache_mode = force_generic_plan" -c "$PREP_D" -c "EXPLAIN $ED" | tr '\n' ' ')
emploie idx_document_meta_date_qa_t104 "$p3" \
  && resultat I06 OK "Plan générique forcé : index encore employé" "" \
  || resultat I06 AVERT "Plan générique forcé : l'index ne sert plus (le code de la métadonnée est un paramètre, l'expression indexée porte une constante) ; sans effet en mode auto (I05)" "$(grep -o 'Seq Scan on document[^ ]*\|Parallel Seq Scan on document' <<<"$p3" | head -1)"

# --- I07 résultats identiques, valeurs mal formées, gain ------------------------------------------
avec=$(q -c "$DATE_C" -c "$MONT_C" | tr '\n' ' ')
# « sans index » en série (max_parallel_workers_per_gather = 0) : le défaut de I08 est mesuré à part.
SERIE=(-c "SET max_parallel_workers_per_gather = 0" -c "SET enable_indexscan = off" -c "SET enable_bitmapscan = off")
sans=$(q "${SERIE[@]}" -c "$DATE_C" -c "$MONT_C" | tr '\n' ' ')
mal=$(q -c "SET max_parallel_workers_per_gather = 0" -c "SELECT count(*) FROM document WHERE metadonnees->>'QA_T104_DATE' IN ('31/12/2026','2026-02-30') AND meta_date(metadonnees, 'QA_T104_DATE') IS NOT NULL" \
        -c "SELECT count(*) FROM document WHERE metadonnees->>'QA_T104_MONTANT' = '12 500,00' AND meta_nombre(metadonnees, 'QA_T104_MONTANT') IS NOT NULL" | tr '\n' ' ')
ta=$(q -c "EXPLAIN (ANALYZE) $DATE_C" | grep -o 'Execution Time: [0-9.]*' | grep -o '[0-9.]*$')
ts=$(q "${SERIE[@]}" -c "EXPLAIN (ANALYZE) $DATE_C" | grep -o 'Execution Time: [0-9.]*' | grep -o '[0-9.]*$')
[[ "$avec" == "$sans" && "$mal" == "0 0 " ]] \
  && resultat I07 OK "Mêmes résultats avec et sans index (lecture en série) ; valeurs mal formées jamais retenues ; plage de 10 jours : ${ta} ms avec index, ${ts} ms sans" "comptes $avec" \
  || resultat I07 ECHEC "Résultats avec et sans index, valeurs mal formées" "avec $avec / sans $sans / mal formées retenues $mal"
# --- I09 plan parallèle forcé, avec et sans index (ANO-E7-007) ------------------------------------
q -c "INSERT INTO document (id, name, noeud_principal_id, type_document_id, size_ko, metadonnees, confidentialite, date_document, statut_indexation, created_at)
      SELECT gen_random_uuid(), 'qa-t104-limite', noeud_principal_id, type_document_id, 1,
             '{\"QA_T104_DATE\":\"0000-01-01\",\"QA_T104_MONTANT\":\"1e1000000\"}'::jsonb, 'PUBLIC', CURRENT_DATE, 'INDEXE', now()
      FROM document WHERE NOT supprime AND name <> 'qa-t104-limite' ORDER BY created_at LIMIT 1" > /dev/null
PARA=(-c "SET parallel_setup_cost = 0" -c "SET parallel_tuple_cost = 0" -c "SET min_parallel_table_scan_size = 0"
      -c "SET min_parallel_index_scan_size = 0" -c "SET max_parallel_workers_per_gather = 2")
SANS_IDX=(-c "SET enable_indexscan = off" -c "SET enable_bitmapscan = off")
DROITS="EXISTS (SELECT 1 FROM document droits_d WHERE droits_d.id = d.id AND droits_d.supprime = false)"
declare -A REQ=(
  [date_large]="SELECT count(*) FROM document d WHERE $DROITS AND meta_date(d.metadonnees, 'QA_T104_DATE') >= DATE '2020-01-01'"
  [date_etroite]="SELECT count(*) FROM document d WHERE $DROITS AND meta_date(d.metadonnees, 'QA_T104_DATE') >= DATE '2023-03-01' AND meta_date(d.metadonnees, 'QA_T104_DATE') <= DATE '2023-03-10'"
  [date_renseignee]="SELECT count(*) FROM document d WHERE $DROITS AND meta_date(d.metadonnees, 'QA_T104_DATE') IS NOT NULL"
  [nombre]="SELECT count(*) FROM document d WHERE $DROITS AND meta_nombre(d.metadonnees, 'QA_T104_MONTANT') >= 1000 AND meta_nombre(d.metadonnees, 'QA_T104_MONTANT') <= 1500"
  [nombre_renseigne]="SELECT count(*) FROM document d WHERE $DROITS AND meta_nombre(d.metadonnees, 'QA_T104_MONTANT') IS NOT NULL")
ok9=0; ko9=""; det9=""
for k in date_large date_etroite date_renseignee nombre nombre_renseigne; do
  ref=$(q -c "SET max_parallel_workers_per_gather = 0" "${SANS_IDX[@]}" -c "${REQ[$k]}")
  for mode in avec_index sans_index; do
    X=("${PARA[@]}"); [[ $mode == sans_index ]] && X+=("${SANS_IDX[@]}")
    npar=$(q "${X[@]}" -c "EXPLAIN ${REQ[$k]}" | grep -c 'Parallel\|Gather')
    r=$(q "${X[@]}" -c "${REQ[$k]}")
    if [[ "$r" =~ ^[0-9]+$ && "$r" == "$ref" && $npar -gt 0 ]]; then ok9=$((ok9 + 1))
    else ko9="$ko9 $k/$mode(plan parallèle : $npar ; série $ref ; parallèle $(tr '\n' ' ' <<<"$r" | cut -c1-120))"; fi
    det9="$det9 $k/$mode=$r"
  done
done
q -c "DELETE FROM document WHERE name = 'qa-t104-limite'" > /dev/null
[[ -z "$ko9" ]] \
  && resultat I09 OK "Plan parallèle forcé (coûts à 0, 2 workers), avec et sans index : date large, étroite, renseignée, nombre ; ligne « 0000-01-01 » / « 1e1000000 » présente : $ok9 requêtes en plan parallèle, mêmes comptes qu'en série [12.7]" "$det9" \
  || resultat I09 ECHEC "Plan parallèle forcé : requête en erreur, sans plan parallèle ou compte différent de la lecture en série [12.7]" "$ko9"

# --- I10 sémantique identique aux corps d'origine (202609301020-1), en série ----------------------
V0="CREATE FUNCTION pg_temp.meta_date_v0(m jsonb, code text) RETURNS date LANGUAGE plpgsql IMMUTABLE AS \$f\$
DECLARE v text := m ->> code;
BEGIN
    IF v IS NULL OR v !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}\$' THEN RETURN NULL; END IF;
    RETURN make_date(substr(v, 1, 4)::int, substr(v, 6, 2)::int, substr(v, 9, 2)::int);
EXCEPTION WHEN others THEN RETURN NULL;
END \$f\$;
CREATE FUNCTION pg_temp.meta_nombre_v0(m jsonb, code text) RETURNS numeric LANGUAGE plpgsql IMMUTABLE AS \$f\$
DECLARE v text := m ->> code;
BEGIN
    IF v IS NULL OR v !~ '^-?[0-9]+(\.[0-9]+)?([eE][-+]?[0-9]+)?\$' THEN RETURN NULL; END IF;
    RETURN v::numeric;
EXCEPTION WHEN others THEN RETURN 'NaN'::numeric;  -- l'ancien corps levait ici une erreur (débordement)
END \$f\$;"
DATES="SELECT to_char(a, 'FM0000') || '-' || to_char(mo, 'FM00') || '-' || to_char(j, 'FM00') AS v
       FROM unnest(ARRAY[0, 1, 4, 100, 1582, 1900, 2000, 2023, 2024, 2100, 9999]) a, generate_series(0, 13) mo, generate_series(0, 32) j
       UNION ALL SELECT unnest(ARRAY['2024-2-29', '20240229', '2024-02-29T00:00', ' 2024-02-29', '2024-02-29 ', '31/12/2026', '', 'abcd-ef-gh', E'2024-02-29\n'])"
NOMBRES="SELECT unnest(ARRAY['0', '-0', '12', '-12.50', '0.000', '00012.3400', '1e3', '1E-3', '-2.5e+10', '12 500,00', '1,5', '.5', '5.', '+5',
          '1e1000000', '1e-1000000', '1e131071', '1e131072', '9e131071', '1e-16383', '1e-16384', '0e1073741823', '0.0e2000000000',
          '1' || repeat('0', 131071), '1' || repeat('0', 131072), '0.' || repeat('0', 16382) || '1', '0.' || repeat('0', 16383) || '1',
          '1e99999999999', 'NaN', 'Infinity', '1e', 'e5', '--1', '']) AS v"
CMP_D="SELECT count(*) || ' dates, ' || count(*) FILTER (WHERE meta_date(jsonb_build_object('c', v), 'c') IS DISTINCT FROM pg_temp.meta_date_v0(jsonb_build_object('c', v), 'c')) || ' écart(s), '
              || count(*) FILTER (WHERE meta_date(jsonb_build_object('c', v), 'c') IS NOT NULL) || ' valides'
       FROM ($DATES) s"
CMP_N="SELECT count(*) || ' nombres, ' || count(*) FILTER (WHERE n0 IS DISTINCT FROM 'NaN' AND n1 IS DISTINCT FROM n0) || ' écart(s), '
              || count(*) FILTER (WHERE n0 = 'NaN') || ' débordement(s) de l''ancien corps (nouveau : '
              || coalesce(string_agg(DISTINCT coalesce(n1::text, 'NULL'), ',') FILTER (WHERE n0 = 'NaN'), '-') || ')'
       FROM (SELECT meta_nombre(jsonb_build_object('c', v), 'c') n1, pg_temp.meta_nombre_v0(jsonb_build_object('c', v), 'c') n0 FROM ($NOMBRES) s0) s"
r10=$(q -c "SET max_parallel_workers_per_gather = 0" -c "$V0" -c "$CMP_D" -c "$CMP_N" | tr '\n' ';')
[[ "$r10" =~ ^[0-9]+\ dates,\ 0\ écart.*\;[0-9]+\ nombres,\ 0\ écart ]] && ! grep -q 'nouveau : [^)]*[0-9]' <<<"$r10" \
  && resultat I10 OK "Sémantique de meta_date et meta_nombre identique aux corps d'origine (valeurs mal formées ou impossibles → NULL) ; débordements de numeric → NULL au lieu d'une erreur" "$r10" \
  || resultat I10 ECHEC "Sémantique de meta_date / meta_nombre différente des corps d'origine" "$r10"

bilan "T-104 index d'expression des métadonnées ($B, $N documents)"
