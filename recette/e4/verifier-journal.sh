#!/usr/bin/env bash
# Recette E4 — inaltérabilité du journal d'audit, au niveau de la base (DAT V3 §7.4.1, §7.4.2 ;
# décision D11).
#
#   J01 structure : colonnes du §7.4.1, partitionnement mensuel, partitions à l'avance ;
#   J02 déclencheur BEFORE UPDATE/DELETE/TRUNCATE sur la table mère ET chaque partition ;
#   J03–J07 CONNECTÉ EN ged_app (vraie connexion, pas SET ROLE) : INSERT et SELECT permis,
#       UPDATE, DELETE, TRUNCATE refusés (SQLSTATE 42501) ;
#   J08–J10 connecté en ged_owner (propriétaire) : UPDATE, DELETE, TRUNCATE refusés par le
#       déclencheur ;
#   J11 table des scellements : ged_app en INSERT et SELECT seulement ;
#   J12 ged_readonly : lecture seule du journal.
# Toute tentative permise s'exécute dans une transaction ANNULÉE : le journal n'est pas modifié.
#
# L'altération réelle (déclencheur désactivé par ged_owner) et sa détection par la vérification
# du scellement sont jouées par verifier-scellement.sh.
#
# Usage : PGHOST=localhost verifier-journal.sh --base NOM [--schema ged]
# Connexions : ged_app, ged_owner, ged_readonly (mots de passe par PGPASSFILE / ~/.pgpass en UAT).

source "$(dirname "${BASH_SOURCE[0]}")/../lib/commun.sh"
BASE=""; SCHEMA=ged
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) BASE="$2"; shift 2 ;;
    --schema) SCHEMA="$2"; shift 2 ;;
    *) fatal "option inconnue : $1" ;;
  esac
done
[[ -n "$BASE" ]] || fatal "--base obligatoire"
trouver_psql
export PGHOST="${PGHOST:-localhost}" PGPORT="${PGPORT:-5432}"
J="$SCHEMA.journal_audit"

# en_tant_que ROLE SQL → sortie « OK » ou « SQLSTATE:message » ; toujours annulé.
en_tant_que() {
  local r
  r="$(PGUSER="$1" PGDATABASE="$BASE" "$PSQL" -X -q -At -v ON_ERROR_STOP=1 -c "BEGIN" -c "$2" -c "ROLLBACK" 2>&1)" \
    && { printf 'OK'; return; }
  printf '%s' "$(tr -d '\r' <<< "$r" | grep -oE '(ERREUR|ERROR)[^\n]*' | head -1)"
}
code_etat() {  # code SQLSTATE d'une erreur psql (VERBOSITY verbose)
  PGUSER="$1" PGDATABASE="$BASE" "$PSQL" -X -q -At -v VERBOSITY=verbose -c "BEGIN" -c "$2" -c "ROLLBACK" 2>&1 \
    | tr -d '\r' | grep -oE '(ERREUR|ERROR) *: *[0-9A-Z]{5}' | grep -oE '[0-9A-Z]{5}$' | head -1
}
sql() { PGUSER=postgres PGDATABASE="$BASE" "$PSQL" -X -q -At -c "$1" | tr -d '\r'; }

# ---------- structure
colonnes="$(sql "SELECT string_agg(column_name, ' ' ORDER BY column_name) FROM information_schema.columns WHERE table_schema='$SCHEMA' AND table_name='journal_audit'")"
manquantes=(); for c in id horodatage acteur_utilisateur_id acteur_application_id adresse_ip action objet_type objet_id avant apres resultat trace_id; do
  [[ " $colonnes " == *" $c "* ]] || manquantes+=("$c")
done
partitionnee="$(sql "SELECT c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='$SCHEMA' AND c.relname='journal_audit'")"
parts="$(sql "SELECT string_agg(c.relname, ' ' ORDER BY c.relname) FROM pg_inherits i JOIN pg_class c ON c.oid=i.inhrelid JOIN pg_class p ON p.oid=i.inhparent JOIN pg_namespace n ON n.oid=p.relnamespace WHERE n.nspname='$SCHEMA' AND p.relname='journal_audit'")"
nb_parts=$(wc -w <<< "$parts")
[[ ${#manquantes[@]} -eq 0 && "$partitionnee" == p && $nb_parts -ge 2 ]] \
  && resultat E4-J01 OK "journal_audit : colonnes du §7.4.1, partitionné par mois [7.4.1, 7.4.3]" "$nb_parts partition(s) : $parts" \
  || resultat E4-J01 ECHEC "journal_audit : colonnes et partitionnement [7.4.1, 7.4.3]" "manquantes : ${manquantes[*]} ; relkind $partitionnee ; partitions : $parts"

sans=()
for t in journal_audit $parts; do
  # bits de pg_trigger.tgtype : 2 BEFORE, 8 DELETE, 16 UPDATE, 32 TRUNCATE
  ev="$(sql "SELECT coalesce(bool_or(tgtype & 16 <> 0), false) || '|' || coalesce(bool_or(tgtype & 8 <> 0), false) || '|' || coalesce(bool_or(tgtype & 32 <> 0), false) FROM pg_trigger g JOIN pg_class c ON c.oid = g.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = '$SCHEMA' AND c.relname = '$t' AND NOT g.tgisinternal AND g.tgenabled <> 'D' AND g.tgtype & 2 <> 0")"
  [[ "$ev" == "true|true|true" ]] || sans+=("$t ($ev)")
done
[[ ${#sans[@]} -eq 0 ]] && resultat E4-J02 OK "Déclencheurs actifs BEFORE UPDATE, DELETE et TRUNCATE sur la table mère et chaque partition [7.4.2]" \
                        || resultat E4-J02 ECHEC "Déclencheurs de refus sur chaque table du journal [7.4.2]" "sans déclencheur complet et actif : ${sans[*]}"

# ---------- connecté en ged_app
id_ligne="$(sql "SELECT min(id) FROM $J")"; [[ -n "$id_ligne" ]] || fatal "journal vide : effectuer au moins une action dans l'application"
ins="$(en_tant_que ged_app "INSERT INTO $J (horodatage, action, objet_type, resultat) VALUES (now(), 'QA_SONDE', 'QA', 'SUCCES')")"
sel="$(en_tant_que ged_app "SELECT count(*) FROM $J")"
[[ "$ins" == OK ]] && resultat E4-J03 OK "ged_app : INSERT permis (transaction annulée) [4.2.3, 7.4.2]" \
                   || resultat E4-J03 ECHEC "ged_app : INSERT permis" "$ins"
[[ "$sel" == OK ]] && resultat E4-J04 OK "ged_app : SELECT permis" || resultat E4-J04 ECHEC "ged_app : SELECT permis" "$sel"
for x in "E4-J05|UPDATE $J SET action = 'QA_ALTERE' WHERE id = $id_ligne" "E4-J06|DELETE FROM $J WHERE id = $id_ligne" "E4-J07|TRUNCATE $J"; do
  id="${x%%|*}"; req="${x#*|}"; c="$(code_etat ged_app "$req")"
  [[ "$c" == 42501 ]] && resultat "$id" OK "Connecté en ged_app : ${req%% *} refusé, SQLSTATE 42501 (privilège) [7.4.2]" \
                      || resultat "$id" ECHEC "Connecté en ged_app : ${req%% *} refusé [7.4.2]" "SQLSTATE ${c:-aucun (accepté ?)} : $(en_tant_que ged_app "$req")"
done

# ---------- connecté en ged_owner (propriétaire : seul le déclencheur l'arrête)
for x in "E4-J08|UPDATE $J SET action = 'QA_ALTERE' WHERE id = $id_ligne" "E4-J09|DELETE FROM $J WHERE id = $id_ligne" "E4-J10|TRUNCATE $J"; do
  id="${x%%|*}"; req="${x#*|}"; r="$(en_tant_que ged_owner "$req")"
  [[ "$r" != OK ]] && resultat "$id" OK "Connecté en ged_owner : ${req%% *} refusé par le déclencheur [7.4.2]" "${r:0:140}" \
                   || resultat "$id" ECHEC "Connecté en ged_owner : ${req%% *} refusé par le déclencheur [7.4.2]" "accepté"
done

# ---------- table des scellements et lecture seule
S="$SCHEMA.journal_audit_scellement"
u="$(code_etat ged_app "UPDATE $S SET empreinte = empreinte")"; d="$(code_etat ged_app "DELETE FROM $S")"; s2="$(en_tant_que ged_app "SELECT count(*) FROM $S")"
[[ "$u" == 42501 && "$d" == 42501 && "$s2" == OK ]] \
  && resultat E4-J11 OK "journal_audit_scellement : ged_app en lecture et ajout seulement [7.4.2]" \
  || resultat E4-J11 ECHEC "journal_audit_scellement : ged_app en lecture et ajout seulement [7.4.2]" "UPDATE ${u:-accepté}, DELETE ${d:-accepté}, SELECT $s2"
ro_sel="$(en_tant_que ged_readonly "SELECT count(*) FROM $J")"; ro_ins="$(code_etat ged_readonly "INSERT INTO $J (horodatage, action, objet_type, resultat) VALUES (now(), 'QA', 'QA', 'SUCCES')")"
[[ "$ro_sel" == OK && "$ro_ins" == 42501 ]] && resultat E4-J12 OK "ged_readonly : lecture du journal, aucune écriture [4.2.3]" \
                                            || resultat E4-J12 ECHEC "ged_readonly : lecture seule" "SELECT $ro_sel, INSERT ${ro_ins:-accepté}"

bilan "E4 journal d'audit $BASE"
