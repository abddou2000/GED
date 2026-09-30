#!/usr/bin/env bash
# Recette tour 2 — P-11 (§6.2.3 A04), P-05 : chaque renvoi « `ClasseTest.methode` » d'un document
# désigne-t-il un test qui existe ? (classe *Test présente sous backend/src/test, méthode déclarée).
# Usage : verifier-renvois-tests.sh [document…] (défaut : MODELE-DE-MENACES.md, SEQUENCES.md, GARANTIE.md)
set -u
W="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
[[ $# -eq 0 ]] && set -- docs/securite/MODELE-DE-MENACES.md docs/modelisation/SEQUENCES.md docs/exploitation/GARANTIE.md
KO=0; OK=0
for doc in "$@"; do
  n_ok=0; manquants=()
  while read -r ref; do
    classe="${ref%%.*}"; methode="${ref#*.}"
    f="$(find "$W/backend/src/test" -name "$classe.java" | head -1)"
    if [[ -n "$f" ]] && grep -qE "(void|[A-Za-z>]) +$methode *\(" "$f"; then n_ok=$((n_ok+1)); else manquants+=("$ref"); fi
  done < <(grep -oE '`[A-Z][A-Za-z0-9]*Test\.[a-z][A-Za-z0-9]*`' "$W/$doc" | tr -d '`' | sort -u)
  OK=$((OK+n_ok)); KO=$((KO+${#manquants[@]}))
  if [[ ${#manquants[@]} -eq 0 ]]; then echo "RESULTAT|renvois-$(basename "$doc" .md)|OK|$n_ok renvoi(s) vers des tests, tous présents|"
  else echo "RESULTAT|renvois-$(basename "$doc" .md)|ECHEC|$n_ok présent(s), ${#manquants[@]} introuvable(s)|${manquants[*]}"; fi
done
echo "BILAN|renvois vers les tests|ok=$OK|introuvables=$KO"
[[ $KO -eq 0 ]]
