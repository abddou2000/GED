@echo off
rem Genere les corpus volumiques T-028 et P-14 (voir LISEZ-MOI.md).
rem Exemple : generer.cmd --jeu t028     generer.cmd --jeu p14 --pages 2000
setlocal
set ICI=%~dp0
pushd "%ICI%..\backend"
call mvn -q test-compile || goto :fin
call mvn -q dependency:build-classpath -Dmdep.outputFile=target\cp.txt || goto :fin
for /f "usebackq delims=" %%a in ("target\cp.txt") do set CP=%%a
java -Xmx4g -Dstdout.encoding=UTF-8 -cp "target\test-classes;target\classes;%CP%" com.ipt.ged.corpus.GenerateurCorpus --sortie "%ICI%sortie" %*
:fin
popd
endlocal
