#!/usr/bin/env bash
# =====================================================================
#  Recette tour 6 — T-025, écart 2 (§12.1, §12.2.1 ; décision du client du 03/10) :
#  groupe_membre désigne l'IDENTITÉ GED (utilisateur_id) ; les appartenances
#  d'employés sans identité attendent dans groupe_membre_attente et sont
#  converties à la première connexion (changesets 202610061000 et 202610061010).
#
#  Base visée : celle de qa (préfixe ged_qa obligatoire), PEUPLÉE, au changeset
#  202610051000 (avant la livraison). Phases, dans l'ordre :
#
#   preparer      données de recette dans l'ANCIEN schéma (groupe_membre.employe_id) :
#                 fiche « QA Nouveau3 » (rattachable au compte simulé qanouveau3, jamais
#                 connecté) et fiche « QAT025 Attente » (sans compte), membres d'un groupe
#                 habilité ; Omar Tazi (sans identité) membre aussi. Idempotent.
#   monter        T025-A1..A5 : montée RÉELLE des deux changesets par Liquibase (outil de
#                 recette, liquibase-core livré) ; groupe_membre + groupe_membre_attente =
#                 lignes d'avant ; chaque ligne retrouvée (même id, même groupe, même
#                 personne) ; schéma attendu ; autres tables inchangées.
#   aller-retour  T025-B1..B6 : rollbackCount 2 (état d'avant retrouvé, contenu comparé
#                 ligne à ligne) puis update (état d'après montée retrouvé, empreinte md5
#                 de chaque table et DDL identiques) ; preuve que la comparaison sait échouer.
#   connexion     T025-C1..C7 (application démarrée, GED_URL) : première connexion de
#                 qanouveau3 par l'annuaire simulé → identité rattachée à la fiche préparée,
#                 appartenance convertie (même id), droit du groupe appliqué (document du
#                 nœud habilité lu : 200), témoins (document privé d'autrui, identité sans
#                 groupe : 404), droits effectifs avec l'origine « groupe ».
#
#  Usage : PGHOST PGPORT PGUSER=ged_owner PGPASSWORD LB_PASSWORD (mêmes valeurs),
#          T025_BASE=ged_qa T025_TRAVAIL=<dossier hors dépôt> \
#          [GED_URL=http://localhost:18084 GED_RECETTE_MOT_DE_PASSE=…] recette-t025-ecart2.sh <phase>
#  Variables facultatives : T025_GROUPE (défaut « Lecteurs Comptabilité »), T025_COMPTE
#  (défaut qanouveau3), T025_PRENOM/T025_NOM (fiche rattachable : QA / Nouveau3),
#  T025_TEMOIN (identité sans groupe, défaut nidrissi), T025_ADMIN (défaut sbennani).
# =====================================================================
set -Eeuo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/commun.sh"
trouver_psql
BASE="${T025_BASE:-ged_qa}"
[[ "$BASE" =~ ^ged_qa(_[a-z0-9_]+)?$ ]] || fatal "base refusée (préfixe ged_qa obligatoire) : $BASE"
refuser_production "$BASE"
: "${T025_TRAVAIL:?T025_TRAVAIL (dossier de travail hors dépôt) obligatoire}"
W="$T025_TRAVAIL"; mkdir -p "$W"
GROUPE="${T025_GROUPE:-Lecteurs Comptabilité}"
COMPTE="${T025_COMPTE:-qanouveau3}"; PRENOM="${T025_PRENOM:-QA}"; NOM="${T025_NOM:-Nouveau3}"
TEMOIN="${T025_TEMOIN:-nidrissi}"; ADMIN="${T025_ADMIN:-sbennani}"
CS_EXPAND=202610061000-1; CS_CONTRACT=202610061010-1

q() { "$PSQL" -X -q -v ON_ERROR_STOP=1 -At -d "$BASE" -c "$1" | tr -d '\r'; }
source "$RECETTE_RACINE/lib/liquibase.sh"
lb() { BACKEND="$DEPOT_RACINE/backend" TMPDIR="$W" LB_URL="jdbc:postgresql://${PGHOST:-localhost}:${PGPORT:-5432}/$BASE" liquibase_executer "$@"; }

nb_cs() { q "select count(*) from ged_liquibase.databasechangelog"; }
cs_present() { q "select count(*) from ged_liquibase.databasechangelog where id = '$1'"; }
colonne() { q "select count(*) from information_schema.columns where table_schema = 'ged' and table_name = '$1' and column_name = '$2'"; }
table() { q "select count(*) from information_schema.tables where table_schema = 'ged' and table_name = '$1'"; }
contrainte() { q "select count(*) from pg_constraint where conname = '$1' and connamespace = 'ged'::regnamespace"; }
index() { q "select count(*) from pg_indexes where schemaname = 'ged' and indexname = '$1'"; }

# Empreinte de chaque table du schéma ged : « table|lignes|md5 ». Les tables du lot sont
# empreintes par colonnes NOMMÉES (l'ordre physique des colonnes change à l'aller-retour) ;
# version_habilitations (compteur d'invalidation du cache des droits, avancé par le
# déclencheur de groupe_membre à chaque passage : effet voulu) est rapporté à part.
empreintes() {
  local t sel
  for t in $(q "select tablename from pg_tables where schemaname = 'ged' order by 1"); do
    case "$t" in
      groupe_membre)
        if [[ "$(colonne groupe_membre employe_id)" == 1 ]]; then sel="x.id::text || '|' || x.groupe_ged_id || '|E:' || x.employe_id"
        else sel="x.id::text || '|' || x.groupe_ged_id || '|U:' || x.utilisateur_id"; fi ;;
      groupe_membre_attente) sel="x.id::text || '|' || x.groupe_ged_id || '|E:' || x.employe_id" ;;
      *) sel="x::text" ;;
    esac
    echo "$t|$(q "select count(*) || '|' || coalesce(md5(string_agg($sel, '#' order by $sel)), '-') from ged.\"$t\" x")"
  done
}
# Appartenances projetées sur (id, groupe, fiche employé), quel que soit le schéma : avant la
# livraison, groupe_membre.employe_id ; après, groupe_membre.utilisateur_id → utilisateur.employe_id,
# plus la table d'attente. Deux projections égales = aucune appartenance perdue, déplacée ou dédoublée.
projection() {
  if [[ "$(colonne groupe_membre employe_id)" == 1 ]]; then
    q "select id || '|' || groupe_ged_id || '|' || employe_id from ged.groupe_membre order by 1"
  else
    q "select x from (select gm.id || '|' || gm.groupe_ged_id || '|' || u.employe_id as x from ged.groupe_membre gm join ged.utilisateur u on u.id = gm.utilisateur_id
                      union all select a.id || '|' || a.groupe_ged_id || '|' || a.employe_id from ged.groupe_membre_attente a) p order by 1"
  fi
}
ddl() {
  "$PG_BIN/pg_dump" -s --no-owner --no-privileges -n ged -d "$BASE" \
    | tr -d '\r' | grep -v -E '^(--|SET |SELECT pg_catalog\.set_config|\\restrict|\\unrestrict)' | sed '/^$/d' > "$1"
}
comparer() {  # $1 $2 fichiers d'empreintes → nombre de lignes différentes (hors version_habilitations)
  diff <(grep -v '^version_habilitations|' "$1") <(grep -v '^version_habilitations|' "$2") > "$W/diff-$(basename "$1")-$(basename "$2").txt" || true
  grep -c '^[<>]' "$W/diff-$(basename "$1")-$(basename "$2").txt" || true
}

phase_preparer() {
  [[ "$(cs_present 202610051000-1)" == 1 && "$(cs_present $CS_EXPAND)" == 0 ]] \
    || fatal "base attendue au changeset 202610051000-1, sans $CS_EXPAND"
  local g; g="$(q "select id from ged.groupe_ged where nom = '$GROUPE'")"
  [[ -n "$g" ]] || fatal "groupe introuvable : $GROUPE"
  [[ "$(q "select count(*) from ged.habilitation where sujet_type = 'GROUPE' and groupe_ged_id = '$g' and noeud_id is not null")" -ge 1 ]] \
    || fatal "le groupe $GROUPE n'a aucune habilitation sur un nœud"
  [[ "$(q "select count(*) from ged.utilisateur where identifiant = '$COMPTE'")" == 0 ]] || fatal "$COMPTE s'est déjà connecté : choisir un autre compte jamais connecté"
  q "insert into ged.employe (id, first_name, last_name, has_user, created_at, updated_at)
     select ged.uuid_v7(), '$PRENOM', '$NOM', false, now(), now()
      where not exists (select 1 from ged.employe where first_name = '$PRENOM' and last_name = '$NOM')" >/dev/null
  q "insert into ged.employe (id, first_name, last_name, has_user, created_at, updated_at)
     select ged.uuid_v7(), 'QAT025', 'Attente', false, now(), now()
      where not exists (select 1 from ged.employe where first_name = 'QAT025' and last_name = 'Attente')" >/dev/null
  local e
  for e in "first_name = '$PRENOM' and last_name = '$NOM'" "first_name = 'QAT025' and last_name = 'Attente'" "first_name = 'Omar' and last_name = 'Tazi'"; do
    q "insert into ged.groupe_membre (id, groupe_ged_id, employe_id)
       select ged.uuid_v7(), '$g', e.id from ged.employe e
        where $e and not exists (select 1 from ged.utilisateur u where u.employe_id = e.id)
       on conflict do nothing" >/dev/null
  done
  # Une seconde appartenance en attente, dans un autre groupe (même fiche, deux groupes).
  q "insert into ged.groupe_membre (id, groupe_ged_id, employe_id)
     select ged.uuid_v7(), g.id, e.id from ged.employe e, ged.groupe_ged g
      where e.first_name = 'QAT025' and e.last_name = 'Attente' and g.nom <> '$GROUPE'
      order by g.nom limit 1
     on conflict do nothing" >/dev/null
  info "groupe_membre : $(q "select count(*) from ged.groupe_membre") lignes, dont $(q "select count(*) from ged.groupe_membre gm where not exists (select 1 from ged.utilisateur u where u.employe_id = gm.employe_id)") de fiches sans identité"
}

phase_monter() {
  [[ "$(cs_present $CS_EXPAND)" == 0 ]] || fatal "$CS_EXPAND déjà appliqué : restaurer la base d'avant"
  projection > "$W/avant.proj"; empreintes > "$W/avant.emp"; ddl "$W/avant.ddl"
  local n0 cs0 sans; n0="$(q "select count(*) from ged.groupe_membre")"; cs0="$(nb_cs)"
  sans="$(q "select count(*) from ged.groupe_membre gm where not exists (select 1 from ged.utilisateur u where u.employe_id = gm.employe_id)")"
  q "select valeur from ged.version_habilitations" > "$W/avant.version"
  lb status > "$W/status.log" 2>&1 || true
  local attendus; attendus="$(grep -a -o -E '2026[0-9]{8}_[a-z_]+\.xml::[0-9-]+::ged' "$W/status.log" | sort -u | tr '\n' ' ')"
  info "avant : $cs0 changesets ; groupe_membre $n0 lignes (dont $sans sans identité) ; en attente d'application : $attendus"
  lb update > "$W/update.log" 2>&1 || { tail -20 "$W/update.log" >&2; fatal "montée Liquibase en échec (voir $W/update.log)"; }
  local cs1 nm na; cs1="$(nb_cs)"; nm="$(q "select count(*) from ged.groupe_membre")"; na="$(q "select count(*) from ged.groupe_membre_attente")"
  if [[ "$cs1" == $((cs0 + 2)) && "$(cs_present $CS_EXPAND)" == 1 && "$(cs_present $CS_CONTRACT)" == 1 ]]; then
    resultat T025-A1 OK "Montée réelle : exactement les deux changesets de la livraison appliqués" "$cs0 → $cs1 ; status avant : $attendus"
  else resultat T025-A1 ECHEC "Montée : changesets inattendus" "$cs0 → $cs1 ; $attendus"; fi
  if [[ $((nm + na)) == "$n0" && "$na" == "$sans" ]]; then
    resultat T025-A2 OK "groupe_membre + groupe_membre_attente = lignes de groupe_membre avant la montée [12.1]" "$nm + $na = $n0 ; attente = fiches sans identité ($sans)"
  else resultat T025-A2 ECHEC "Décompte des appartenances après montée" "$nm + $na ≠ $n0 (sans identité avant : $sans)"; fi
  projection > "$W/apres.proj"
  if diff -q "$W/avant.proj" "$W/apres.proj" >/dev/null; then
    resultat T025-A3 OK "Chaque appartenance retrouvée avec son identifiant, son groupe et sa personne (identité ↔ fiche par utilisateur.employe_id)" "$(wc -l < "$W/avant.proj") lignes projetées identiques"
  else resultat T025-A3 ECHEC "Appartenances modifiées par la montée" "$(diff "$W/avant.proj" "$W/apres.proj" | head -4 | tr '\n' ' ')"; fi
  local s="employe_id absent=$((1 - $(colonne groupe_membre employe_id))) utilisateur_id non nul=$(q "select count(*) from information_schema.columns where table_schema='ged' and table_name='groupe_membre' and column_name='utilisateur_id' and is_nullable='NO'")"
  s+=" uk=$(contrainte uk_groupe_membre_groupe_ged_id_utilisateur_id) fk=$(contrainte fk_groupe_membre_utilisateur) idx=$(index idx_groupe_membre_utilisateur_id)"
  s+=" attente: table=$(table groupe_membre_attente) uk=$(contrainte uk_groupe_membre_attente_groupe_ged_id_employe_id) fk_groupe=$(contrainte fk_groupe_membre_attente_groupe_ged) fk_employe=$(contrainte fk_groupe_membre_attente_employe) idx=$(index idx_groupe_membre_attente_employe_id)"
  local refus; refus="$("$PSQL" -X -q -At -d "$BASE" -c "begin; insert into ged.groupe_membre (id, groupe_ged_id) select ged.uuid_v7(), id from ged.groupe_ged limit 1; rollback" 2>&1 | grep -o 'violates not-null constraint' || true)"
  if [[ "$s" != *"=0"* && -n "$refus" ]]; then
    resultat T025-A4 OK "Schéma après montée : membre = identité obligatoire, contraintes et index nommés selon §4.2.2, table d'attente" "$s ; insertion sans utilisateur_id refusée"
  else resultat T025-A4 ECHEC "Schéma après montée" "$s ; refus=${refus:-aucun}"; fi
  empreintes > "$W/apres.emp"
  diff <(grep -v -E '^(version_habilitations|groupe_membre|groupe_membre_attente)\|' "$W/avant.emp") \
       <(grep -v -E '^(version_habilitations|groupe_membre|groupe_membre_attente)\|' "$W/apres.emp") > "$W/diff-autres.txt" || true
  if [[ ! -s "$W/diff-autres.txt" ]]; then
    resultat T025-A5 OK "Aucune autre table touchée par la montée" "$(grep -c . "$W/apres.emp") tables empreintes ; version_habilitations $(cat "$W/avant.version") → $(q "select valeur from ged.version_habilitations") (déclencheur de groupe_membre)"
  else resultat T025-A5 ECHEC "Autres tables modifiées par la montée" "$(head -4 "$W/diff-autres.txt" | tr '\n' ' ')"; fi
  ddl "$W/apres.ddl"
}

phase_aller_retour() {
  [[ -s "$W/avant.emp" && -s "$W/apres.emp" ]] || fatal "phase monter d'abord"
  [[ "$(cs_present $CS_CONTRACT)" == 1 ]] || fatal "$CS_CONTRACT absent"
  empreintes > "$W/t1.emp"; ddl "$W/t1.ddl"; projection > "$W/t1.proj"
  local cs1; cs1="$(nb_cs)"
  lb rollbackCount 2 > "$W/rollback.log" 2>&1 || { tail -20 "$W/rollback.log" >&2; fatal "retour arrière en échec (voir $W/rollback.log)"; }
  local cs2; cs2="$(nb_cs)"
  if [[ "$cs2" == $((cs1 - 2)) && "$(cs_present $CS_EXPAND)" == 0 && "$(cs_present $CS_CONTRACT)" == 0 ]]; then
    resultat T025-B1 OK "Retour arrière des deux changesets (contract puis expand) exécuté" "$cs1 → $cs2"
  else resultat T025-B1 ECHEC "Retour arrière : changesets inattendus" "$cs1 → $cs2"; fi
  empreintes > "$W/rb.emp"; ddl "$W/rb.ddl"; projection > "$W/rb.proj"
  local d; d="$(comparer "$W/avant.emp" "$W/rb.emp")"
  if [[ "$d" == 0 ]] && diff -q "$W/avant.proj" "$W/rb.proj" >/dev/null && [[ "$(table groupe_membre_attente)" == 0 && "$(colonne groupe_membre utilisateur_id)" == 0 ]]; then
    resultat T025-B2 OK "Après retour arrière : contenu de TOUTES les tables identique à l'état d'avant la montée (appartenances en attente réintégrées, mêmes identifiants)" "$(grep -c . "$W/rb.emp") tables ; groupe_membre $(q "select count(*) from ged.groupe_membre") lignes (id, groupe, employe_id) ; table d'attente et utilisateur_id supprimés"
  else resultat T025-B2 ECHEC "Après retour arrière : état d'avant non retrouvé" "$d ligne(s) d'empreinte différentes : $(head -4 "$W/diff-avant.emp-rb.emp.txt" | tr '\n' ' ' | cut -c1-300)"; fi
  # DDL : comparaison du schéma, à l'ordre physique des colonnes près (rapporté à part).
  local dd; dd="$(diff "$W/avant.ddl" "$W/rb.ddl" | grep -c '^[<>]' || true)"
  local ddtri; ddtri="$(diff <(sed "s/,$//" "$W/avant.ddl" | sort) <(sed "s/,$//" "$W/rb.ddl" | sort) | grep -c '^[<>]' || true)"
  if [[ "$dd" == 0 ]]; then
    resultat T025-B3 OK "Après retour arrière : DDL du schéma ged identique à celui d'avant la montée" "pg_dump -s normalisé"
  elif [[ "$ddtri" == 0 ]]; then
    resultat T025-B3 AVERT "Après retour arrière : DDL identique au tri des lignes près (ordre physique des colonnes de groupe_membre)" "$dd ligne(s) déplacées : $(diff "$W/avant.ddl" "$W/rb.ddl" | grep '^[<>]' | head -4 | tr '\n' ' ' | cut -c1-300)"
  else resultat T025-B3 ECHEC "Après retour arrière : DDL différent" "$ddtri ligne(s) : $(diff <(sed "s/,$//" "$W/avant.ddl" | sort) <(sed "s/,$//" "$W/rb.ddl" | sort) | grep '^[<>]' | head -6 | tr '\n' ' ' | cut -c1-400)"; fi
  lb update > "$W/update2.log" 2>&1 || { tail -20 "$W/update2.log" >&2; fatal "remontée en échec (voir $W/update2.log)"; }
  local cs3; cs3="$(nb_cs)"
  empreintes > "$W/t2.emp"; ddl "$W/t2.ddl"; projection > "$W/t2.proj"
  d="$(comparer "$W/t1.emp" "$W/t2.emp")"
  if [[ "$cs3" == "$cs1" && "$d" == 0 ]] && diff -q "$W/t1.proj" "$W/t2.proj" >/dev/null; then
    resultat T025-B4 OK "Remontée : contenu de toutes les tables identique à l'état d'après la première montée (membres et attente, mêmes identifiants)" "$cs2 → $cs3 changesets ; $(grep -c . "$W/t2.emp") tables, md5 identiques"
  else resultat T025-B4 ECHEC "Remontée : état d'après montée non retrouvé" "$cs3 changesets ; $d ligne(s) : $(head -4 "$W/diff-t1.emp-t2.emp.txt" | tr '\n' ' ' | cut -c1-300)"; fi
  if diff -q "$W/t1.ddl" "$W/t2.ddl" >/dev/null; then
    resultat T025-B5 OK "Remontée : DDL identique à celui d'après la première montée" "pg_dump -s normalisé"
  else resultat T025-B5 ECHEC "Remontée : DDL différent" "$(diff "$W/t1.ddl" "$W/t2.ddl" | grep '^[<>]' | head -4 | tr '\n' ' ' | cut -c1-300)"; fi
  # La comparaison sait échouer : l'état d'avant et l'état d'après montée diffèrent bien.
  d="$(comparer "$W/avant.emp" "$W/t2.emp")"
  if [[ "$d" -gt 0 ]]; then
    resultat T025-B6 OK "Autocontrôle : la comparaison distingue l'état d'avant de l'état d'après montée" "$d ligne(s) d'empreinte différentes (groupe_membre, groupe_membre_attente)"
  else resultat T025-B6 ECHEC "Autocontrôle : la comparaison ne distingue rien" ""; fi
  info "version_habilitations : $(cat "$W/avant.version") avant ; $(q "select valeur from ged.version_habilitations") après l'aller-retour (déclencheur, exclu des comparaisons)"
}

phase_connexion() {
  : "${GED_URL:?GED_URL obligatoire}"; : "${GED_RECETTE_MOT_DE_PASSE:?GED_RECETTE_MOT_DE_PASSE obligatoire}"
  export GED_CHAMP_IDENTIFIANT=identifiant
  source "$RECETTE_RACINE/lib/api.sh"
  connecter() { _JETON=""; GED_RECETTE_IDENTIFIANT="$1" api_connexion; }
  local e g n0 a_id doc prive
  e="$(q "select id from ged.employe where first_name = '$PRENOM' and last_name = '$NOM'")"
  g="$(q "select id from ged.groupe_ged where nom = '$GROUPE'")"
  a_id="$(q "select id from ged.groupe_membre_attente where employe_id = '$e' and groupe_ged_id = '$g'")"
  n0="$(q "select (select count(*) from ged.groupe_membre) + (select count(*) from ged.groupe_membre_attente)")"
  local noeud; noeud="$(q "select noeud_id from ged.habilitation where sujet_type = 'GROUPE' and groupe_ged_id = '$g' and noeud_id is not null limit 1")"
  doc="$(q "select id from ged.document where noeud_principal_id = '$noeud' and confidentialite = 'PUBLIC' and statut_conservation = 'ACTIF' and not supprime order by created_at limit 1")"
  prive="$(q "select id from ged.document where noeud_principal_id = '$noeud' and confidentialite = 'PRIVE' and not supprime order by created_at limit 1")"
  local v0; v0="$(q "select valeur from ged.version_habilitations")"
  if [[ -n "$e" && -n "$a_id" && "$(q "select count(*) from ged.utilisateur where identifiant = '$COMPTE' or employe_id = '$e'")" == 0 ]]; then
    resultat T025-C1 OK "Avant la connexion : fiche « $PRENOM $NOM » sans identité, appartenance au groupe « $GROUPE » en attente" "fiche $e ; ligne d'attente $a_id"
  else resultat T025-C1 ECHEC "État préalable inattendu" "fiche=${e:-?} attente=${a_id:-?}"; fi
  # Vue de l'Administrateur avant la connexion : la fiche figure dans pendingUserIds.
  local avant_api="non lu"
  if connecter "$ADMIN"; then
    api_appel GET "/api/v1/access-groups/$g"; avant_api="HTTP $HTTP_CODE, pendingUserIds contient la fiche : $(grep -q "\"pendingUserIds\"[^]]*$e" "$HTTP_CORPS" && echo oui || echo non)"
  fi
  # Première connexion par l'annuaire simulé.
  local t0; t0="$(date +%s)"
  if connecter "$COMPTE"; then
    local u; u="$(q "select id from ged.utilisateur where identifiant = '$COMPTE'")"
    resultat T025-C2 OK "Première connexion de $COMPTE par l'annuaire simulé (search-then-bind)" "HTTP 200 ; identité $u ; Administrateur avant : $avant_api"
    local rat conv rest n1 v1
    rat="$(q "select count(*) from ged.utilisateur where id = '$u' and employe_id = '$e'")"
    conv="$(q "select count(*) from ged.groupe_membre where id = '$a_id' and groupe_ged_id = '$g' and utilisateur_id = '$u'")"
    rest="$(q "select count(*) from ged.groupe_membre_attente where employe_id = '$e'")"
    n1="$(q "select (select count(*) from ged.groupe_membre) + (select count(*) from ged.groupe_membre_attente)")"
    v1="$(q "select valeur from ged.version_habilitations")"
    if [[ "$rat" == 1 && "$conv" == 1 && "$rest" == 0 && "$n1" == "$n0" && "$v1" -gt "$v0" ]]; then
      resultat T025-C3 OK "Appartenance convertie à la première connexion, sans action de l'Administrateur : même identifiant de ligne, identité rattachée à la fiche préparée" "groupe_membre $a_id → utilisateur $u ; attente de la fiche vidée ; total $n0 inchangé ; autres en attente : $(q "select count(*) from ged.groupe_membre_attente") ; version_habilitations $v0 → $v1"
    else resultat T025-C3 ECHEC "Conversion à la première connexion" "rattachée=$rat convertie=$conv reste_en_attente=$rest total $n0→$n1 version $v0→$v1"; fi
    api_appel GET "/api/v1/auth/me"
    local roles; roles="$(tr -d '\r\n' < "$HTTP_CORPS" | grep -o '"roles":\[[^]]*\]' || true)"
    api_appel GET "/api/v1/documents/$doc"
    if [[ "$HTTP_CODE" == 200 ]]; then
      resultat T025-C4 OK "Droit du groupe appliqué : le nouveau membre lit un document du nœud habilité pour le groupe" "GET /documents/$doc → 200 ; /auth/me $roles"
    else resultat T025-C4 ECHEC "Droit du groupe non appliqué" "GET /documents/$doc → $HTTP_CODE ; $roles"; fi
    api_appel GET "/api/v1/documents/$prive"; local c_prive="$HTTP_CODE"
    connecter "$TEMOIN" || true
    api_appel GET "/api/v1/documents/$doc"; local c_temoin="$HTTP_CODE"
    if [[ "$c_prive" == 404 && "$c_temoin" == 404 ]]; then
      resultat T025-C5 OK "Témoins : document PRIVÉ d'autrui refusé au nouveau membre ; identité sans groupe ($TEMOIN) sans accès au document public" "$COMPTE → $prive : 404 ; $TEMOIN → $doc : 404"
    else resultat T025-C5 ECHEC "Témoins" "$COMPTE → privé : $c_prive ; $TEMOIN → public : $c_temoin"; fi
    if connecter "$ADMIN"; then
      api_appel GET "/api/v1/admin/droits-effectifs?utilisateurId=$u&documentId=$doc"
      local orig; orig="$(tr -d '\r\n' < "$HTTP_CORPS" | grep -o '"role":"[^"]*","habilitationId"[^}]*"via":"[^"]*","viaLibelle":"[^"]*"' | sed -E 's/"habilitationId".*"via"/"via"/' | sort -u | tr '\n' ' ')"
      if [[ "$HTTP_CODE" == 200 ]] && grep -q -F "\"via\":\"GROUPE\",\"viaLibelle\":\"$GROUPE\"" "$HTTP_CORPS"; then
        resultat T025-C6 OK "Droits effectifs (P-22) : l'accès au document a pour origine le groupe « $GROUPE »" "HTTP 200 ; origines : $orig"
      else resultat T025-C6 ECHEC "Droits effectifs : origine groupe absente" "HTTP $HTTP_CODE ; $(head -c 300 "$HTTP_CORPS")"; fi
      api_appel GET "/api/v1/access-groups/$g"
      if [[ "$HTTP_CODE" == 200 ]] && ! grep -q "\"pendingUserIds\"[^]]*$e" "$HTTP_CORPS" && grep -q "$e" "$HTTP_CORPS"; then
        resultat T025-C7 OK "Vue de l'Administrateur : la personne passe de pendingUserIds à users, sans ressaisie" "avant : $avant_api ; après : membre (fiche $e dans users)"
      else resultat T025-C7 ECHEC "Vue de l'Administrateur après connexion" "HTTP $HTTP_CODE ; avant : $avant_api"; fi
    fi
  else
    resultat T025-C2 ECHEC "Première connexion de $COMPTE refusée" "HTTP $HTTP_CODE $(head -c 200 "$HTTP_CORPS")"
  fi
  info "durée $(( $(date +%s) - t0 )) s"
}

# Retour arrière APRÈS mise en service (application arrêtée) : des appartenances ont été
# converties à la première connexion ; aucune ne doit se perdre ni changer de personne.
phase_retour_apres_service() {
  [[ "$(cs_present $CS_CONTRACT)" == 1 ]] || fatal "$CS_CONTRACT absent"
  local conv; conv="$(q "select count(*) from ged.groupe_membre gm join ged.utilisateur u on u.id = gm.utilisateur_id where u.identifiant = '$COMPTE'")"
  projection > "$W/s1.proj"; empreintes > "$W/s1.emp"; ddl "$W/s1.ddl"
  local cs1; cs1="$(nb_cs)"
  lb rollbackCount 2 > "$W/rollback-s.log" 2>&1 || { tail -20 "$W/rollback-s.log" >&2; fatal "retour arrière en échec"; }
  projection > "$W/s2.proj"
  if diff -q "$W/s1.proj" "$W/s2.proj" >/dev/null && [[ "$conv" -ge 1 ]]; then
    resultat T025-D1 OK "Retour arrière après première connexion : toutes les appartenances (converties et en attente) reviennent dans groupe_membre avec leur identifiant et leur fiche" "$(wc -l < "$W/s2.proj") lignes projetées identiques ; dont $conv convertie(s) pour $COMPTE"
  else resultat T025-D1 ECHEC "Retour arrière après première connexion" "converties=$conv ; $(diff "$W/s1.proj" "$W/s2.proj" | head -4 | tr '\n' ' ')"; fi
  lb update > "$W/update-s.log" 2>&1 || { tail -20 "$W/update-s.log" >&2; fatal "remontée en échec"; }
  empreintes > "$W/s3.emp"; ddl "$W/s3.ddl"
  local d; d="$(comparer "$W/s1.emp" "$W/s3.emp")"
  if [[ "$(nb_cs)" == "$cs1" && "$d" == 0 ]] && diff -q "$W/s1.ddl" "$W/s3.ddl" >/dev/null; then
    resultat T025-D2 OK "Remontée : état d'après mise en service retrouvé (contenu de toutes les tables et DDL identiques)" "$(grep -c . "$W/s3.emp") tables"
  else resultat T025-D2 ECHEC "Remontée après mise en service" "$d ligne(s) : $(head -4 "$W/diff-s1.emp-s3.emp.txt" | tr '\n' ' ' | cut -c1-300)"; fi
}

case "${1:-}" in
  preparer) phase_preparer ;;
  retour-apres-service) phase_retour_apres_service ;;
  monter) phase_monter ;;
  aller-retour) phase_aller_retour ;;
  connexion) phase_connexion ;;
  *) fatal "phase attendue : preparer | monter | aller-retour | connexion" ;;
esac
bilan "T-025 écart 2 ($1)"
