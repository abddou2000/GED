#!/usr/bin/env bash
# Recette E10 — P-14 / R30 (tour 7) : réglage de débit OCR livré au tour 6 (dev3, fusion 5ceb08c),
# contrôlé sur l'APPLICATION démarrée (JAR), de bout en bout : démarrage avec conversion des modèles,
# OCR d'un dépôt réel de PDF scanné et recherche, repli sans combine_tessdata, retour au réglage d'avant.
#
# Le binaire Tesseract est remplacé par un ESPION (script) qui note, pour chaque appel de l'application,
# le répertoire de modèles (--tessdata-dir), OMP_THREAD_LIMIT et les dimensions de l'image reçue sur
# l'entrée standard (A4 : 1654 x 2339 à 200 dpi, 2480 x 3508 à 300 dpi), puis appelle le vrai Tesseract.
# Un lien combine_tessdata est posé à côté de l'espion (recherche par défaut « à côté du binaire »).
#
# Phases (une instance par phase, arrêtée à la fin de la phase) :
#   A  défaut         (aucune variable GED_OCR_*) : conversion au démarrage, modèles entiers, 200 dpi
#   A2 redémarrage    copie compactée réutilisée (non refaite)
#   B  repli          GED_OCR_COMBINE_TESSDATA vers un chemin inexistant : avertissement, OCR fonctionnel
#   B3 outil rétabli  même répertoire de travail que B, outil par défaut : la conversion doit se faire,
#                     dépôt, débit retrouvé (CPU Tesseract d'une page, B / B3 ≥ 1,3) (ANO-E6-003)
#   B4 3e démarrage   même répertoire : rien de refait (dates et marques inchangées)
#   B5 ancienne marque marques « copie » d'avant 4fc5b95 : conversion refaite, outil présent
#   B2 répertoire     répertoire de la copie impossible à créer : avertissement, modèles livrés
# Tour 8 : à chaque phase démarrée, ligne « Réglage OCR : modèles … (…), pages PDF rendues à … dpi » du journal
# et champs modeles / dpi de GET /api/v1/ocr/etat (46171b8) ; l'espion note aussi le temps CPU de Tesseract.
#   C  retour         GED_OCR_DPI=300 et GED_OCR_MODELES=precis : modèles livrés, 300 dpi
#   D  valeur refusée GED_OCR_MODELES=rapide : démarrage refusé
#
# Prérequis : base et annuaire de l'instance (variables de l'application déjà exportées : DB_*,
# SERVER_PORT, GED_MANAGEMENT_PORT, annuaire…), JAR construit, Tesseract et combine_tessdata réels.
# Variables : GED_JAR (défaut backend/target/ged-0.0.1-SNAPSHOT.jar), GED_TESSERACT_REEL (défaut
#   /usr/bin/tesseract), GED_COMBINE_REEL (défaut à côté), GED_TESSDATA (défaut backend/tessdata),
#   GED_RECETTE_IDENTIFIANT / GED_RECETTE_MOT_DE_PASSE (compte qui dépose), GED_TYPE_CODE (défaut TD-FACT),
#   GED_P14_ARGS (arguments Spring de plus, ex. --spring.profiles.active=dev), GED_P14_TRAVAIL (répertoire
#   de travail, défaut temporaire conservé). Linux (bash, curl, jq, file).

source "$(dirname "${BASH_SOURCE[0]}")/../lib/commun.sh"
B="$DEPOT_RACINE/backend"
JAR="${GED_JAR:-$B/target/ged-0.0.1-SNAPSHOT.jar}"
TESS="${GED_TESSERACT_REEL:-/usr/bin/tesseract}"
CT="${GED_COMBINE_REEL:-$(dirname "$TESS")/combine_tessdata}"
TD="$(cd "${GED_TESSDATA:-$B/tessdata}" && pwd)"
PORT="${SERVER_PORT:-8080}"; MGMT="${GED_MANAGEMENT_PORT:-8081}"
U="http://localhost:$PORT"
TYPE_CODE="${GED_TYPE_CODE:-TD-FACT}"
: "${GED_RECETTE_IDENTIFIANT:?GED_RECETTE_IDENTIFIANT obligatoire}" "${GED_RECETTE_MOT_DE_PASSE:?GED_RECETTE_MOT_DE_PASSE obligatoire}"
[[ -f "$JAR" ]] || fatal "JAR absent : $JAR"
[[ -x "$TESS" && -x "$CT" ]] || fatal "Tesseract ou combine_tessdata absent ($TESS, $CT)"
command -v jq >/dev/null && command -v file >/dev/null || fatal "jq et file requis"
W="${GED_P14_TRAVAIL:-$(mktemp -d "${TMPDIR:-/tmp}/qa-p14.XXXXXX")}"; mkdir -p "$W/bin" "$W/tmp" "$W/coffre" "$W/cache"
info "travail : $W ; JAR : $JAR ; port $PORT / $MGMT"
# Témoin inédit fait de syllabes (un chiffre de l'heure = une syllabe) : prononçable, donc moins exposé aux
# erreurs d'un caractère que l'OCR commet sur une suite de lettres arbitraire (premier essai du tour 7 :
# « septokarinabgcdcg » lu « septokarinabgcedcg »).
SYL=(ba de fi go lu ma no pi ro tu); MARQUE=""; for c in $(date +%H%M%S | grep -o .); do MARQUE+="${SYL[$c]}"; done

# ---- espion Tesseract
cat > "$W/bin/tesseract" <<EOF
#!/usr/bin/env bash
if [[ "\$1" == stdin ]]; then
  f="\$(mktemp "$W/tmp/espion.XXXXXX")"; cat > "\$f"
  d="\$(file -b "\$f" | grep -o '[0-9]* x [0-9]*' | head -1)"
  td=""; prev=""; for x in "\$@"; do [[ "\$prev" == --tessdata-dir ]] && td="\$x"; prev="\$x"; done
  "$TESS" "\$@" < "\$f"; rc=\$?
  # Temps CPU exact de Tesseract (utilisateur + système des processus fils attendus) : builtin times.
  times > "\$f.t"; cpu="\$(tail -1 "\$f.t" | awk '{s=0; for(i=1;i<=2;i++){split(\$i,a,"m"); sub("s","",a[2]); s+=a[1]*60+a[2]} printf "%.2f", s}')"
  echo "\$(date +%s)|\${ESPION_PHASE:-?}|tessdata=\$td|omp=\${OMP_THREAD_LIMIT:-}|image=\$d|cpu=\$cpu|args=\$*" >> "$W/espion.log"
  cp "\$f" "$W/image-\${ESPION_PHASE:-x}"; printf '%s\n' "\$*" > "$W/args-\${ESPION_PHASE:-x}"   # pour rejouer (débit)
  rm -f "\$f" "\$f.t"; exit \$rc
fi
exec "$TESS" "\$@"
EOF
chmod +x "$W/bin/tesseract"
ln -sf "$CT" "$W/bin/combine_tessdata"

# ---- PDF scannés témoins (classpath du back-end, hors ligne)
(cd "$B" && mvn -o -B -q dependency:build-classpath -Dmdep.outputFile="$W/cp.txt" -Dmdep.includeScope=runtime) \
  || fatal "classpath du backend introuvable hors ligne"
for p in a b c d; do
  java -Dfile.encoding=UTF-8 -cp "$(cat "$W/cp.txt")" "$RECETTE_RACINE/e10/ScanTemoin.java" "$W/scan-$p.pdf" \
    "septokarin${p}${MARQUE}" 2>/dev/null || fatal "génération du scan $p"
done

# ---- conversion indépendante (référence) : mêmes empreintes attendues que la copie de l'application
mkdir -p "$W/reference"
for m in ara fra; do cp "$TD/$m.traineddata" "$W/reference/" && "$CT" -c "$W/reference/$m.traineddata" >/dev/null 2>&1; done

PID=""
demarrer() {  # demarrer <phase> [VAR=valeur…] : lance l'instance et attend la sonde de vie (ou l'arrêt)
  local phase="$1"; shift
  JETON=""
  : > "$W/app-$phase.log"
  ( cd "$W" && exec env "$@" ESPION_PHASE="$phase" GED_TESSERACT="$W/bin/tesseract" GED_TESSDATA="$TD" \
      GED_STOCKAGE_RACINE="$W/coffre" GED_CACHE_APERCU_RACINE="$W/cache" GED_ANTIVIRUS_ACTIF=false \
      java -Xmx1g -Djava.io.tmpdir="$W/tmp" -jar "$JAR" ${GED_P14_ARGS:-} > "$W/app-$phase.log" 2>&1 ) &
  PID=$!
  local t0=$SECONDS
  for _ in $(seq 1 150); do
    [[ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$MGMT/actuator/health/liveness")" == 200 ]] && { info "$phase : prête en $((SECONDS - t0)) s"; return 0; }
    kill -0 "$PID" 2>/dev/null || { info "$phase : processus arrêté après $((SECONDS - t0)) s"; return 1; }
    sleep 2
  done
  return 1
}
arreter() {
  [[ -n "$PID" ]] || return 0
  pkill -TERM -P "$PID" 2>/dev/null; kill "$PID" 2>/dev/null
  for _ in $(seq 1 40); do curl -s -o /dev/null "http://localhost:$MGMT/actuator/health/liveness" || break; sleep 1; done
  wait "$PID" 2>/dev/null; PID=""
}
trap arreter EXIT

JETON=""
connecter() {
  local r w
  [[ -n "$JETON" ]] && return 0
  for _ in 1 2 3 4; do
    r="$(curl -s -D "$W/h" -H 'Content-Type: application/json' \
         -d "$(jq -nc --arg i "$GED_RECETTE_IDENTIFIANT" --arg m "$GED_RECETTE_MOT_DE_PASSE" '{identifiant:$i,motDePasse:$m}')" "$U/api/v1/auth/login")"
    JETON="$(jq -r '.token // .accessToken // empty' <<<"$r" 2>/dev/null)"
    [[ -n "$JETON" ]] && return 0
    w="$(grep -i '^retry-after:' "$W/h" | tr -dc '0-9')"; sleep $(( ${w:-15} + 1 ))
  done
  return 1
}
get() { curl -s -H "Authorization: Bearer $JETON" "$U$1"; }

# deposer_et_chercher <phase> <scan> <témoin> : dépôt, attente de l'OCR, recherche ; résultats <phase>.depot / .ocr / .recherche
deposer_et_chercher() {
  local ph="$1" scan="$2" temoin="$3" type r code id t0 st texte
  connecter || { resultat "P14.$ph.depot" ECHEC "connexion" "refusée"; return; }
  type="$(get "/api/v1/type-documents?size=200" | jq -r --arg c "$TYPE_CODE" '[.content[]? | select(.code==$c)][0].id // empty')"
  [[ -n "$type" ]] || { resultat "P14.$ph.depot" ECHEC "type $TYPE_CODE introuvable" ""; return; }
  r="$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer $JETON" -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" \
       -F "file=@$scan;type=application/pdf" -F "name=qa-p14-$ph-$MARQUE" -F "typeDocumentId=$type" "$U/api/v1/documents")"
  code="$(tail -1 <<<"$r")"; id="$(head -1 <<<"$r" | jq -r '.id // .document.id // empty')"
  if [[ "$code" == 202 ]] && head -1 <<<"$r" | grep -q EN_ATTENTE_OCR; then
    resultat "P14.$ph.depot" OK "dépôt d'un PDF scanné (sans couche texte) : 202 EN_ATTENTE_OCR" "document $id"
  else
    resultat "P14.$ph.depot" ECHEC "dépôt d'un PDF scanné : 202 EN_ATTENTE_OCR attendu" "HTTP $code $(head -1 <<<"$r" | cut -c1-200)"; return
  fi
  t0=$SECONDS
  for _ in $(seq 1 90); do
    r="$(get "/api/v1/ocr/documents/$id/texte")"; st="$(jq -r '.statutOcr // ""' <<<"$r")"
    [[ "$(jq -r '.interrogeable' <<<"$r")" == true || "$st" == OCR_ECHEC ]] && break
    sleep 2
  done
  texte="$(jq -r '.texte // ""' <<<"$r")"
  if [[ "$(jq -r '.interrogeable' <<<"$r")" == true ]] && grep -q "$temoin" <<<"$texte"; then
    resultat "P14.$ph.ocr" OK "texte extrait par OCR, témoin lu" "en $((SECONDS - t0)) s ; statut $st, provenance $(jq -r .provenance <<<"$r"), $(jq -r .nbPages <<<"$r") page, langue $(jq -r .langue <<<"$r"), $(jq -r .longueur <<<"$r") caractères"
  else
    resultat "P14.$ph.ocr" ECHEC "texte extrait par OCR, témoin lu" "statut $st ; extrait : $(head -c 200 <<<"$texte")"
  fi
  r="$(get "/api/v1/recherche/plein-texte?taille=50&q=$temoin")"
  if grep -q "$id" <<<"$r"; then
    resultat "P14.$ph.recherche" OK "document trouvé par la recherche plein texte sur le témoin" "q=$temoin"
  else
    resultat "P14.$ph.recherche" ECHEC "document trouvé par la recherche plein texte sur le témoin" "q=$temoin : $(head -c 200 <<<"$r")"
  fi
  r="$(get "/api/v1/recherche/plein-texte?taille=50&q=$(jq -rn --arg q 'قرطاسيون' '$q|@uri')")"
  if grep -q "$id" <<<"$r"; then resultat "P14.$ph.recherche-ar" OK "témoin arabe trouvé" "قرطاسيون"
  else resultat "P14.$ph.recherche-ar" AVERT "témoin arabe trouvé (information)" "$(grep -o 'الاستلام[^"]*' <<<"$texte" | head -1)"; fi
}

# espion <phase> <tessdata attendu> <dpi attendu> : appels de Tesseract faits par l'application pendant la phase.
# Largeur attendue d'une page A4 (595,28 pt) : 595,28 × dpi / 72, à 2 pixels près (PDFBox tronque : 1653 à 200 dpi).
espion() {
  local ph="$1" td="$2" dpi="$3" l larg att
  l="$(grep "|$ph|" "$W/espion.log" 2>/dev/null | tail -1)"
  if [[ -z "$l" ]]; then resultat "P14.$ph.espion" ECHEC "appel de Tesseract observé" "aucun"; return; fi
  larg="$(grep -o 'image=[0-9]*' <<<"$l" | cut -d= -f2)"; att=$(( 59528 * dpi / 7200 ))
  if [[ "$l" == *"|tessdata=$td|"* && "$l" == *"|omp=1|"* && -n "$larg" && ${larg:-0} -ge $((att - 2)) && ${larg:-0} -le $((att + 2)) ]]; then
    resultat "P14.$ph.espion" OK "Tesseract appelé avec les modèles et la résolution attendus" "$(cut -d'|' -f3-5 <<<"$l") ($dpi dpi)"
  else
    resultat "P14.$ph.espion" ECHEC "Tesseract appelé avec les modèles et la résolution attendus" "attendu tessdata=$td omp=1 largeur ~$att px ($dpi dpi) ; vu $(cut -d'|' -f3-5 <<<"$l")"
  fi
}

etat_ocr() {  # etat_ocr <phase> <modeles attendus> <dpi attendu> (champs modeles et dpi : tour 8, 46171b8)
  local r; connecter >/dev/null; r="$(get /api/v1/ocr/etat)"
  if [[ "$(jq -r .moteurDisponible <<<"$r")" == true ]] && jq -e '.languesInstallees | index("ara") and index("fra")' <<<"$r" >/dev/null \
     && [[ "$(jq -r .modeles <<<"$r")" == "$2" && "$(jq -r .dpi <<<"$r")" == "$3" ]]; then
    resultat "P14.$1.etat" OK "/api/v1/ocr/etat : moteur disponible, ara et fra installées, modeles=$2, dpi=$3" "$(jq -c '{moteurDisponible,languesInstallees,langueDefaut,modeles,dpi}' <<<"$r")"
  else
    resultat "P14.$1.etat" ECHEC "/api/v1/ocr/etat : modeles=$2, dpi=$3 attendus" "$r"
  fi
}

reglage() {  # reglage <phase> <modeles> <répertoire> <dpi> : ligne « Réglage OCR » du journal de démarrage (46171b8)
  local l att="Réglage OCR : modèles $2 ($3), pages PDF rendues à $4 dpi"
  l="$(grep -a -o 'Réglage OCR : .*' "$W/app-$1.log" | head -1)"
  if [[ "$l" == "$att" ]]; then resultat "P14.$1.reglage" OK "journal : réglage OCR employé" "$l"
  else resultat "P14.$1.reglage" ECHEC "journal : « $att » attendu" "${l:-ligne absente}"; fi
}

marques() {  # marques <répertoire> : « ara=entiers fra=entiers … » (modes écrits à côté des copies)
  local f d=""
  for f in "$1"/*.traineddata.source-sha256; do [[ -f "$f" ]] && d+="$(basename "$f" .traineddata.source-sha256)=$(cut -d' ' -f2 "$f") "; done
  echo "${d% }"
}

cpu() {  # cpu <phase> : temps CPU de Tesseract (s) au dernier appel OCR de la phase
  grep "|$1|" "$W/espion.log" 2>/dev/null | tail -1 | grep -o 'cpu=[0-9.]*' | cut -d= -f2
}

rejouer() {  # rejouer <phase> : CPU minimal (s) de 5 appels de Tesseract avec l'image et les arguments reçus de l'application
  local i c best="" args
  [[ -f "$W/image-$1" && -f "$W/args-$1" ]] || return 0
  read -r -a args < "$W/args-$1"
  for i in 1 2 3 4 5; do
    c="$( ( OMP_THREAD_LIMIT=1 "$TESS" "${args[@]}" < "$W/image-$1" > /dev/null 2>&1; times ) | tail -1 \
         | awk '{s=0; for(i=1;i<=2;i++){split($i,a,"m"); sub("s","",a[2]); s+=a[1]*60+a[2]} printf "%.2f", s}')"
    [[ -z "$best" ]] || awk -v c="$c" -v b="$best" 'BEGIN{exit !(c < b)}' && best="$c"
  done
  echo "$best"
}

empreintes_ok() {  # empreintes_ok <répertoire> : ara et fra identiques à la conversion indépendante
  local m
  for m in ara fra; do cmp -s "$1/$m.traineddata" "$W/reference/$m.traineddata" || return 1; done
}

ENT="$W/tmp/ged-tessdata-entiers"
sha256sum "$TD"/*.traineddata > "$W/livres-avant.sha"
# ================= A : défaut
if demarrer A; then
  l="$(grep -a 'Modèles OCR compactés en entiers' "$W/app-A.log" | head -1)"
  if [[ "$l" == *"$ENT"* && "$l" == *ara* && "$l" == *fra* ]]; then
    resultat P14.A.journal OK "démarrage : conversion des modèles au journal (défaut, sous java.io.tmpdir)" "$(grep -o 'Modèles OCR.*' <<<"$l" | cut -c1-200)"
  else
    resultat P14.A.journal ECHEC "démarrage : conversion des modèles au journal" "${l:-ligne absente}"
  fi
  reglage A entiers "$ENT" 200
  ok=1; d=""
  for m in ara fra; do
    s1="$(sha256sum < "$ENT/$m.traineddata" | cut -c1-64)"; s2="$(sha256sum < "$W/reference/$m.traineddata" | cut -c1-64)"
    e="$(cat "$ENT/$m.traineddata.source-sha256" 2>/dev/null)"; so="$(sha256sum < "$TD/$m.traineddata" | cut -c1-64)"
    [[ "$s1" == "$s2" && "$e" == "$so entiers" ]] || ok=0
    d+="$m $(wc -c < "$TD/$m.traineddata") -> $(wc -c < "$ENT/$m.traineddata") o, empreinte ${s1:0:12}… (référence ${s2:0:12}…) ; "
  done
  [[ $ok == 1 ]] && resultat P14.A.modeles OK "copie compactée identique à une conversion indépendante (combine_tessdata -c), empreinte de la source notée" "$d marques : $(marques "$ENT")" \
                 || resultat P14.A.modeles ECHEC "copie compactée identique à une conversion indépendante" "$d marques : $(marques "$ENT")"
  etat_ocr A entiers 200
  deposer_et_chercher A "$W/scan-a.pdf" "septokarina$MARQUE"
  espion A "$ENT" 200
  stat -c '%Y' "$ENT/fra.traineddata" "$ENT/ara.traineddata" > "$W/mtime-A"
else
  resultat P14.A.demarrage ECHEC "démarrage avec le réglage par défaut" "$(grep -a -m3 -i 'error\|exception' "$W/app-A.log" | cut -c1-200)"
fi
arreter
sleep 2
# ================= A2 : redémarrage
if demarrer A2; then
  stat -c '%Y' "$ENT/fra.traineddata" "$ENT/ara.traineddata" > "$W/mtime-A2"
  if cmp -s "$W/mtime-A" "$W/mtime-A2" && grep -aq 'Modèles OCR compactés en entiers' "$W/app-A2.log"; then
    resultat P14.A2.reutilisation OK "redémarrage : copie compactée réutilisée, non refaite" "mtime inchangé ; $(grep -ao 'Modèles OCR compactés.*' "$W/app-A2.log" | head -1 | cut -c1-120)"
  else
    resultat P14.A2.reutilisation ECHEC "redémarrage : copie compactée réutilisée" "$(paste -sd' ' "$W/mtime-A") / $(paste -sd' ' "$W/mtime-A2")"
  fi
  reglage A2 entiers "$ENT" 200
fi
arreter
sleep 2
# ================= B : repli sans combine_tessdata
EB="$W/entiers-B"
if demarrer B GED_OCR_COMBINE_TESSDATA=/inexistant/combine_tessdata GED_OCR_MODELES_ENTIERS_REPERTOIRE="$EB"; then
  l="$(grep -a 'Aucun modèle OCR compacté' "$W/app-B.log" | head -1)"
  if [[ "$l" == *WARN* && "$l" == *"/inexistant/combine_tessdata indisponible)"* ]]; then
    resultat P14.B.journal OK "combine_tessdata absent : avertissement « outil indisponible » au journal, démarrage poursuivi" "$(grep -o 'Aucun modèle.*' <<<"$l" | cut -c1-260)"
  else
    resultat P14.B.journal ECHEC "combine_tessdata absent : avertissement « … indisponible) » au journal" "${l:-ligne absente}"
  fi
  reglage B repli "$TD" 200
  mq="$(marques "$EB")"
  [[ "$mq" == *ara=repli* && "$mq" == *fra=repli* ]] && resultat P14.B.marques OK "copies marquées « repli » (outil indisponible, à refaire)" "$mq" \
                                                     || resultat P14.B.marques ECHEC "copies marquées « repli »" "$mq"
  etat_ocr B repli 200
  deposer_et_chercher B "$W/scan-b.pdf" "septokarinb$MARQUE"
  espion B "$TD" 200
else
  resultat P14.B.demarrage ECHEC "démarrage sans combine_tessdata" "$(grep -a -m3 -i 'error\|exception' "$W/app-B.log" | cut -c1-200)"
fi
arreter
sleep 2
# ================= B3 : outil rétabli (même répertoire de travail que B, combine_tessdata par défaut)
# Remède documenté (DEPLOIEMENT.md § 3.2, EXPLOITATION.md § 2) : installer combine_tessdata et redémarrer.
if demarrer B3 GED_OCR_MODELES_ENTIERS_REPERTOIRE="$EB"; then
  l="$(grep -a 'Modèles OCR compactés en entiers\|Aucun modèle OCR compacté' "$W/app-B3.log" | head -1)"
  if [[ "$l" == *"Modèles OCR compactés en entiers"*ara*fra* ]] && ! grep -aq 'Aucun modèle OCR' "$W/app-B3.log"; then
    resultat P14.B3.reprise OK "outil rétabli : conversion faite au redémarrage suivant, sans avertissement" "$(grep -o 'Modèles OCR.*' <<<"$l" | cut -c1-160)"
  else
    resultat P14.B3.reprise ECHEC "outil rétabli : conversion faite au redémarrage suivant" "$(grep -o 'Modèles OCR.*\|Aucun modèle.*' <<<"$l" | cut -c1-160) ; marques : $(marques "$EB")"
  fi
  mq="$(marques "$EB")"
  if [[ "$mq" == *ara=entiers* && "$mq" == *fra=entiers* ]] && empreintes_ok "$EB"; then
    resultat P14.B3.modeles OK "copies refaites, identiques à la conversion indépendante, marques « entiers »" "$mq"
  else
    resultat P14.B3.modeles ECHEC "copies refaites et identiques à la conversion indépendante" "$mq"
  fi
  reglage B3 entiers "$EB" 200
  etat_ocr B3 entiers 200
  deposer_et_chercher B3 "$W/scan-d.pdf" "septokarind$MARQUE"
  espion B3 "$EB" 200
  # Débit : CPU de Tesseract lors de l'appel fait par l'application (une mesure, sensible à la charge du poste),
  # puis minimum de 5 rejeux du même appel (même image, mêmes arguments, donc mêmes modèles) pour B, B3 et A.
  cb="$(cpu B)"; c3="$(cpu B3)"; ca="$(cpu A)"
  rb="$(rejouer B)"; r3="$(rejouer B3)"; ra="$(rejouer A)"
  if [[ -n "$rb" && -n "$r3" ]] && awk -v b="$rb" -v t="$r3" 'BEGIN{exit !(t > 0 && b / t >= 1.3)}'; then
    resultat P14.B3.debit OK "débit retrouvé : CPU Tesseract de la page en B3 (entiers) nettement sous celui de B (repli)" "minimum de 5 rejeux : B $rb s -> B3 $r3 s (×$(awk -v b="$rb" -v t="$r3" 'BEGIN{printf "%.2f", b/t}')), A (défaut) $ra s ; appel de l'application : B $cb s, B3 $c3 s, A $ca s"
  else
    resultat P14.B3.debit ECHEC "débit retrouvé (rapport B / B3 ≥ 1,3 attendu)" "minimum de 5 rejeux : B ${rb:-?} s, B3 ${r3:-?} s, A ${ra:-?} s ; appel de l'application : B ${cb:-?} s, B3 ${c3:-?} s, A ${ca:-?} s"
  fi
  stat -c '%n %Y' "$EB"/*.traineddata "$EB"/*.source-sha256 > "$W/mtime-B3"
else
  resultat P14.B3.demarrage ECHEC "démarrage, outil rétabli" "$(grep -a -m3 -i 'error\|exception' "$W/app-B3.log" | cut -c1-200)"
fi
arreter
sleep 2
# ================= B4 : troisième démarrage, même répertoire, sans reconversion
if demarrer B4 GED_OCR_MODELES_ENTIERS_REPERTOIRE="$EB"; then
  stat -c '%n %Y' "$EB"/*.traineddata "$EB"/*.source-sha256 > "$W/mtime-B4"
  if cmp -s "$W/mtime-B3" "$W/mtime-B4" && grep -aq 'Modèles OCR compactés en entiers' "$W/app-B4.log" && ! grep -aq 'Aucun modèle OCR' "$W/app-B4.log"; then
    resultat P14.B4.reutilisation OK "troisième démarrage : copies et marques réutilisées, rien de refait (osd non convertible compris)" "$(wc -l < "$W/mtime-B4") fichiers, dates inchangées ; $(marques "$EB")"
  else
    resultat P14.B4.reutilisation ECHEC "troisième démarrage : rien de refait" "$(diff "$W/mtime-B3" "$W/mtime-B4" | tr '\n' ' ' | cut -c1-200)"
  fi
  reglage B4 entiers "$EB" 200
  etat_ocr B4 entiers 200
else
  resultat P14.B4.demarrage ECHEC "troisième démarrage" "$(grep -a -m3 -i 'error\|exception' "$W/app-B4.log" | cut -c1-200)"
fi
arreter
sleep 2
# ================= B5 : marque « copie » des versions précédentes (répertoire persistant d'une installation antérieure)
E5="$W/entiers-B5"; mkdir -p "$E5"
for f in "$TD"/*.traineddata; do
  cp "$f" "$E5/"; echo "$(sha256sum < "$f" | cut -c1-64) copie" > "$E5/$(basename "$f").source-sha256"
done
if demarrer B5 GED_OCR_MODELES_ENTIERS_REPERTOIRE="$E5"; then
  mq="$(marques "$E5")"
  if grep -aq 'Modèles OCR compactés en entiers.*ara.*fra' "$W/app-B5.log" && [[ "$mq" == *ara=entiers* && "$mq" == *fra=entiers* ]] && empreintes_ok "$E5"; then
    resultat P14.B5.ancienne-marque OK "marque « copie » d'avant la correction : conversion refaite, outil présent" "$mq"
  else
    resultat P14.B5.ancienne-marque ECHEC "marque « copie » : conversion refaite" "$(grep -ao 'Modèles OCR.*\|Aucun modèle.*' "$W/app-B5.log" | head -1 | cut -c1-160) ; $mq"
  fi
  reglage B5 entiers "$E5" 200
else
  resultat P14.B5.demarrage ECHEC "démarrage sur d'anciennes marques" "$(grep -a -m3 -i 'error\|exception' "$W/app-B5.log" | cut -c1-200)"
fi
arreter
sleep 2
# ================= B2 : répertoire de travail impossible à créer (repli, autre message)
if demarrer B2 GED_OCR_MODELES_ENTIERS_REPERTOIRE=/proc/ged-tessdata-entiers; then
  l="$(grep -a 'Répertoire des modèles compactés\|Aucun modèle OCR compacté' "$W/app-B2.log" | head -1)"
  if [[ "$l" == *WARN* ]]; then
    resultat P14.B2.journal OK "répertoire de la copie inutilisable : avertissement, modèles livrés employés" "$(grep -o 'Répertoire des.*\|Aucun modèle.*' <<<"$l" | cut -c1-200)"
  else
    resultat P14.B2.journal ECHEC "répertoire de la copie inutilisable : avertissement" "${l:-ligne absente}"
  fi
  reglage B2 repli "$TD" 200
  etat_ocr B2 repli 200
else
  resultat P14.B2.demarrage ECHEC "démarrage avec un répertoire de copie inutilisable" "$(grep -a -m3 -i 'error\|exception' "$W/app-B2.log" | cut -c1-200)"
fi
arreter
sleep 2
# ================= C : retour au réglage d'avant
if demarrer C GED_OCR_DPI=300 GED_OCR_MODELES=precis GED_OCR_MODELES_ENTIERS_REPERTOIRE="$W/entiers-C"; then
  if ! grep -aq 'Modèles OCR compactés\|Aucun modèle OCR compacté' "$W/app-C.log" && [[ ! -e "$W/entiers-C" ]]; then
    resultat P14.C.journal OK "precis : aucune conversion (ni journal, ni répertoire de travail)" ""
  else
    resultat P14.C.journal ECHEC "precis : aucune conversion" "$(grep -a 'Modèles OCR' "$W/app-C.log" | head -1 | cut -c1-200)"
  fi
  reglage C precis "$TD" 300
  etat_ocr C precis 300
  deposer_et_chercher C "$W/scan-c.pdf" "septokarinc$MARQUE"
  espion C "$TD" 300
else
  resultat P14.C.demarrage ECHEC "démarrage en precis à 300 dpi" "$(grep -a -m3 -i 'error\|exception' "$W/app-C.log" | cut -c1-200)"
fi
arreter
sleep 2
# ================= D : valeur inconnue
if demarrer D GED_OCR_MODELES=rapide; then
  resultat P14.D.refus ECHEC "GED_OCR_MODELES=rapide : démarrage refusé" "l'application a démarré"
else
  l="$(grep -a -o 'ged.ocr.modeles : [^"]*reçu « rapide »' "$W/app-D.log" | head -1)"
  [[ -n "$l" ]] && resultat P14.D.refus OK "GED_OCR_MODELES=rapide : démarrage refusé, motif explicite" "$l" \
                || resultat P14.D.refus AVERT "GED_OCR_MODELES=rapide : démarrage refusé" "motif non retrouvé au journal"
fi
arreter
if sha256sum -c --quiet "$W/livres-avant.sha" >/dev/null 2>&1; then
  resultat P14.livres OK "modèles livrés intacts après les quatre phases (seule source du registre et du SBOM)" "$(wc -l < "$W/livres-avant.sha") modèles, empreintes inchangées"
else
  resultat P14.livres ECHEC "modèles livrés intacts" "empreinte modifiée"
fi
info "espion : $(wc -l < "$W/espion.log" 2>/dev/null) appels de Tesseract notés ($W/espion.log)"
bilan "P-14 application"
