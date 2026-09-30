#!/usr/bin/env bash
# =====================================================================
#  Instance PostgreSQL jetable pour les tests des scripts de sauvegarde.
#  À sourcer. Crée un cluster dans un répertoire temporaire, écoute sur une
#  socket Unix de ce répertoire seulement (aucun port TCP), authentification
#  trust ; jamais l'instance partagée. Lancé par root, le cluster appartient
#  au compte postgres (initdb refuse root).
#
#  Après instance_demarrer : PGHOST, PGPORT, PGUSER=postgres exportés ;
#  JETABLE = répertoire de travail (supprimé par instance_arreter).
# =====================================================================

instance_bin() {
    if [[ -n "${PG_BIN:-}" ]]; then echo "$PG_BIN"; return; fi
    if command -v pg_config >/dev/null 2>&1 && [[ -x "$(pg_config --bindir)/initdb" ]]; then
        pg_config --bindir; return
    fi
    ls -d /usr/lib/postgresql/*/bin 2>/dev/null | sort -V | tail -1
}

instance_demarrer() {
    PG_BIN="$(instance_bin)"
    [[ -x "$PG_BIN/initdb" ]] || { echo "initdb introuvable (PG_BIN)" >&2; return 1; }
    JETABLE="$(mktemp -d "${TMPDIR:-/tmp}/ged-pg-jetable.XXXXXX")"
    chmod 0755 "$JETABLE"
    local executer=()
    if [[ "$(id -u)" == 0 ]]; then
        chown postgres: "$JETABLE"
        executer=(runuser -u postgres --)
    fi
    "${executer[@]}" "$PG_BIN/initdb" -D "$JETABLE/data" -U postgres -A trust -E UTF8 --no-instructions >/dev/null
    PGPORT_JETABLE="${PGPORT_JETABLE:-$(( 56000 + RANDOM % 1000 ))}"
    "${executer[@]}" "$PG_BIN/pg_ctl" -D "$JETABLE/data" -l "$JETABLE/pg.log" -w \
        -o "-p $PGPORT_JETABLE -k $JETABLE -c listen_addresses=''" start >/dev/null
    export PGHOST="$JETABLE" PGPORT="$PGPORT_JETABLE" PGUSER=postgres
    export PGOPTIONS="-c client_min_messages=warning"
    unset PGPASSWORD PGDATABASE
    export PATH="$PG_BIN:$PATH"
    INSTANCE_EXECUTER=("${executer[@]}")
}

instance_arreter() {
    [[ -n "${JETABLE:-}" && -d "$JETABLE/data" ]] || return 0
    "${INSTANCE_EXECUTER[@]}" "$PG_BIN/pg_ctl" -D "$JETABLE/data" -m immediate -w stop >/dev/null 2>&1 || true
    rm -rf "$JETABLE"
}

# Résultats : ok "libellé" / ko "libellé" ; bilan final.
ECHECS=0
ok() { echo "  [OK]    $*"; }
ko() { echo "  [ÉCHEC] $*"; ECHECS=$((ECHECS + 1)); }
verifier() { if eval "$2"; then ok "$1"; else ko "$1"; fi; }
bilan() {
    if (( ECHECS == 0 )); then echo "RÉSULTAT : RÉUSSI"; return 0; fi
    echo "RÉSULTAT : $ECHECS contrôle(s) en échec"; return 1
}
