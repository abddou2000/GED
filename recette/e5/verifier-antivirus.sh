#!/usr/bin/env bash
# Recette E5 — « Un fichier infecté est refusé » (critère de sortie E5 ; DAT §6.1.5, §12.11).
#
#   V01 témoin : un fichier sain du même format est accepté (sinon un refus de l'EICAR ne
#       prouverait rien : il pourrait être refusé pour son type ou sa taille) ;
#   V02 EICAR → HTTP 422, code FICHIER_INFECTE, rien d'écrit dans le stockage ;
#   V03 EICAR sous une extension .pdf → refusé (415 par le type réel ou 422), rien d'écrit ;
#   V04 (--antivirus-arrete) ClamAV arrêté par l'exploitant → dépôt d'un fichier SAIN refusé
#       en HTTP 503 ANTIVIRUS_INDISPONIBLE (échec fermé), rien d'écrit ;
#   V05 (GED_API_AUDIT, livré en E4) événement d'audit du refus.
#
# Le type de document doit accepter le texte brut (format autorisé par défaut, §6.1.5) :
# GED_TYPE_DOCUMENT_TEXTE_ID. Le fichier EICAR est créé dans un dossier temporaire puis supprimé.
# Usage : verifier-antivirus.sh --url URL [--racine STOCKAGE] [--antivirus-arrete] [--nettoyer]

ARRETE=0; ARGS=()
for a in "$@"; do [[ "$a" == --antivirus-arrete ]] && ARRETE=1 || ARGS+=("$a"); done
source "$(dirname "${BASH_SOURCE[0]}")/commun-e5.sh" "${ARGS[@]}"
connexion_ou_abandon
TYPE="${GED_TYPE_DOCUMENT_TEXTE_ID:-$(type_document_par_defaut)}"
info "type de document : $TYPE ; stockage : ${RACINE_STOCKAGE:-non vérifié}"
EICAR="$API_TMP/eicar.txt"; ecrire_eicar "$EICAR"
[[ "$(wc -c < "$EICAR")" -eq 68 ]] || fatal "chaîne EICAR altérée"

if [[ "$ARRETE" == 0 ]]; then
  if ! depot_accepte E5-V01 "Témoin : fichier texte sain accepté par ce type" "$DONNEES/note_texte_brut.txt" "temoin-antivirus.txt" "$TYPE"; then
    info "le témoin est refusé : choisir un type acceptant text/plain (GED_TYPE_DOCUMENT_TEXTE_ID) ; V02 n'aurait aucune valeur de preuve"
  fi
  depot_refuse E5-V02 "EICAR refusé : 422 FICHIER_INFECTE, rien d'écrit [6.1.5]" "$EICAR" "facture-eicar.txt" "$TYPE" 422 FICHIER_INFECTE
  api_depot "$EICAR" "rapport-eicar.pdf" "$TYPE"; [[ -n "$DEPOT_ID" ]] && DEPOSES+=("$DEPOT_ID")
  if [[ "$HTTP_CODE" == 415 || "$HTTP_CODE" == 422 ]]; then resultat E5-V03 OK "EICAR déguisé en .pdf refusé [6.1.5]" "HTTP $HTTP_CODE $(code_metier)"
  else resultat E5-V03 ECHEC "EICAR déguisé en .pdf refusé [6.1.5]" "HTTP $HTTP_CODE ${DEPOT_ID:+(accepté, id $DEPOT_ID)}"; fi
  resultat E5-V04 NA "Échec fermé si ClamAV est indisponible" "relancer avec --antivirus-arrete après arrêt de clamd par l'exploitant"
else
  depot_refuse E5-V04 "ClamAV indisponible : fichier sain refusé, 503 ANTIVIRUS_INDISPONIBLE [6.1.5]" \
    "$DONNEES/note_texte_brut.txt" "sain-sans-antivirus.txt" "$TYPE" 503 ANTIVIRUS_INDISPONIBLE
fi

if [[ -n "${GED_API_AUDIT:-}" ]]; then
  api_appel GET "$GED_API_AUDIT"
  grep -q 'FICHIER_INFECTE\|DEPOT_REFUSE' "$HTTP_CORPS" \
    && resultat E5-V05 OK "Refus antivirus audité [6.1.5, 7.4.1]" \
    || resultat E5-V05 ECHEC "Refus antivirus audité [6.1.5, 7.4.1]" "aucun événement FICHIER_INFECTE dans $GED_API_AUDIT"
else
  resultat E5-V05 NA "Refus antivirus audité" "journal d'audit livré en E4 (GED_API_AUDIT)"
fi
bilan "E5 antivirus"
