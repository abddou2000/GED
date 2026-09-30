#!/bin/bash
# =====================================================================
#  GED Marchica Med — ajoute pgaudit à shared_preload_libraries (P-16)
#  SANS écraser la liste en place (pg_stat_statements, auto_explain…).
#
#  Usage (superutilisateur, sur le serveur de base) :
#     activer-pgaudit.sh [options de connexion psql]    ex. : -U postgres -d postgres
#  puis redémarrer PostgreSQL. Idempotent : ne fait rien si pgaudit est déjà
#  dans la liste.
#
#  Pourquoi pas une ligne dans pgaudit.conf : une seconde ligne
#  shared_preload_libraries dans un fichier inclus remplace la première, et
#  les bibliothèques déjà chargées disparaîtraient au redémarrage.
#  ALTER SYSTEM écrit dans postgresql.auto.conf, lu en dernier.
# =====================================================================
set -euo pipefail

psql_() { psql -X -A -t -v ON_ERROR_STOP=1 "$@"; }

# Valeur qui s'appliquera au prochain redémarrage : la dernière des fichiers de
# configuration (postgresql.auto.conf compris), sinon la valeur en cours. Lire
# seulement SHOW ferait ajouter pgaudit deux fois si le script est relancé
# avant le redémarrage.
actuel=$(psql_ "$@" -c "SELECT coalesce(
    (SELECT setting FROM pg_file_settings WHERE name = 'shared_preload_libraries'
      ORDER BY seqno DESC LIMIT 1),
    current_setting('shared_preload_libraries'))")

# La valeur est une liste : éléments séparés par des virgules, éventuellement
# entre guillemets doubles. Chaque élément est repris tel quel.
elements=()
IFS=',' read -r -a brut <<< "$actuel"
for e in "${brut[@]:-}"; do
    e="${e#"${e%%[![:space:]]*}"}"; e="${e%"${e##*[![:space:]]}"}"; e="${e//\"/}"
    [ -n "$e" ] || continue
    if [ "$e" = pgaudit ]; then
        echo "pgaudit est déjà dans shared_preload_libraries ($actuel) : rien à faire."
        exit 0
    fi
    case "$e" in *[!A-Za-z0-9_./$-]*) echo "Élément inattendu dans shared_preload_libraries : $e" >&2; exit 1;; esac
    elements+=("$e")
done
elements+=(pgaudit)

# Chaque bibliothèque entre apostrophes : 'a,b' serait lu comme UNE bibliothèque
# nommée « a,b » (paramètre de type liste).
liste=$(printf "'%s', " "${elements[@]}"); liste=${liste%, }
psql_ "$@" -c "ALTER SYSTEM SET shared_preload_libraries = $liste" > /dev/null
echo "shared_preload_libraries : '${actuel}' -> ${liste}"
echo "Redémarrer PostgreSQL pour charger pgaudit, puis exécuter pgaudit-roles.sql."
