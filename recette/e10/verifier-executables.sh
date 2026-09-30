#!/usr/bin/env bash
# Recette tour 2 — ANO-E10-003 (T-073, T-092, T-093) : les scripts de deploiement/ sont-ils
# exécutables tels que le dépôt les livre ?
# 1. modes Git (git ls-files -s) : tout script appelé directement doit être en 100755 ;
#    seuls les fichiers « sourcés » (commun*.sh) peuvent rester en 100644 ;
# 2. installation depuis le dépôt (git archive, qui conserve les modes) dans une racine jetable,
#    puis `systemd-analyze verify --root` des unités livrées (ged-backend, ged-sauvegarde) ;
# 3. appel direct, dans la racine, de chaque script appelé par un autre (ou par systemd /
#    archive_command) : `--aide` ou sans argument, on ne regarde que l'absence de « Permission denied ».
# Usage : verifier-executables.sh [racine-jetable]
set -u
W="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
R="${1:-${TMPDIR:-/tmp}/qa-r2-racine-systemd}"
OK=0; KO=0
res() { if [[ "$2" == OK ]]; then OK=$((OK+1)); else KO=$((KO+1)); fi; echo "RESULTAT|$1|$2|$3"; }

echo "== 1. modes du dépôt"
while read -r mode _ _ chemin; do
  base="$(basename "$chemin")"
  if [[ "$base" == commun*.sh ]]; then
    # un fichier sourcé ne doit être appelé nulle part directement
    if grep -rqE "(^|[[:space:]\"'/])(\\\$DIR[A-Z_]*/|\./)?$base([[:space:]]|\"|$)" "$W/deploiement" --include='*.service' --include='*.conf*' 2>/dev/null; then
      res "E10003-mode-$base" KO "fichier sourcé appelé directement"
    else
      res "E10003-mode-$base" OK "$mode (sourcé seulement)"
    fi
  elif [[ "$mode" == 100755 ]]; then
    res "E10003-mode-$base" OK "$mode"
  else
    res "E10003-mode-$base" KO "$mode (non exécutable)"
  fi
done < <(git -C "$W" ls-files -s -- 'deploiement/*.sh')

echo "== 2. installation depuis le dépôt (git archive) et systemd-analyze verify"
rm -rf "$R"; mkdir -p "$R/opt/ged" "$R/etc/systemd/system" "$R/usr/bin"
git -C "$W" archive HEAD deploiement | tar -x -C "$R/opt/ged"
cp "$R/opt/ged/deploiement/sauvegarde/ged-sauvegarde.service" "$R/opt/ged/deploiement/sauvegarde/ged-sauvegarde.timer" \
   "$R/opt/ged/deploiement/systemd/ged-backend.service" "$R/etc/systemd/system/"
cp "$(readlink -f "$(command -v java)")" "$R/usr/bin/java"   # seul le droit d'exécution compte pour verify
cp "$(command -v mkdir)" "$R/usr/bin/mkdir"
echo "mode installé de sauvegarde-quotidienne.sh : $(stat -c %A "$R/opt/ged/deploiement/sauvegarde/sauvegarde-quotidienne.sh")"
sortie="$(systemd-analyze verify --root="$R" ged-sauvegarde.service ged-sauvegarde.timer ged-backend.service 2>&1)"
echo "$sortie"
if grep -q "is not executable" <<<"$sortie"; then
  res E10003-systemd KO "systemd : script non exécutable"
else
  res E10003-systemd OK "systemd-analyze verify sans « not executable »"
fi

echo "== 3. appels directs dans la racine installée"
for s in sauvegarde/sauvegarde-quotidienne.sh sauvegarde/archiver-wal.sh sauvegarde/sauvegarder-base.sh \
         sauvegarde/sauvegarder-fichiers.sh sauvegarde/sauvegarder-cles.sh sauvegarde/restaurer.sh \
         sauvegarde/rapprocher-orphelins.sh scripts/deployer.sh scripts/test-fumee.sh; do
  f="$R/opt/ged/deploiement/$s"
  sortie="$(cd /tmp && timeout 10 "$f" --aide </dev/null 2>&1)"; code=$?
  if [[ $code -eq 126 ]] || grep -q "Permission denied" <<<"$sortie"; then
    res "E10003-appel-$(basename "$s")" KO "code $code : $(head -c 160 <<<"$sortie")"
  else
    res "E10003-appel-$(basename "$s")" OK "exécuté (code $code)"
  fi
done
echo "BILAN|OK=$OK|KO=$KO"
[[ $KO -eq 0 ]]
