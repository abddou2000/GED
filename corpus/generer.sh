#!/usr/bin/env bash
# Génère les corpus volumiques T-028 et P-14 (voir LISEZ-MOI.md).
# Exemple : ./generer.sh --jeu t028      ./generer.sh --jeu p14 --pages 2000
set -euo pipefail
ICI="$(cd "$(dirname "$0")" && pwd)"
cd "$ICI/../backend"
mvn -q test-compile
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -Xmx4g -Dstdout.encoding=UTF-8 -cp "target/test-classes:target/classes:$(cat target/cp.txt)" \
  com.ipt.ged.corpus.GenerateurCorpus --sortie "$ICI/sortie" "$@"
