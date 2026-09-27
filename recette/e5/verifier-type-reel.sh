#!/usr/bin/env bash
# Recette E5 — type réel déterminé par le contenu (Apache Tika), format non autorisé → 415
# (DAT §6.1.5, §5.3.2).
#
# Le type de document visé (GED_TYPE_DOCUMENT_PDF_ID) n'accepte QUE le PDF.
#   R01 témoin : vrai PDF accepté ;
#   R02 texte brut nommé .pdf → 415 FORMAT_NON_AUTORISE, rien d'écrit ;
#   R03 en-tête d'exécutable (MZ/PE) nommé .pdf → 415 ;
#   R04 DOCX nommé .pdf → 415 ;
#   R05 PNG nommé .pdf → 415 ;
#   R06 vrai PDF nommé .txt → accepté : c'est le contenu qui fait foi, pas l'extension
#       (un refus ici n'est pas contraire au DAT mais plus strict : AVERT, à confirmer).
# Usage : verifier-type-reel.sh --url URL [--racine STOCKAGE] [--nettoyer]

source "$(dirname "${BASH_SOURCE[0]}")/commun-e5.sh" "$@"
connexion_ou_abandon
TYPE="${GED_TYPE_DOCUMENT_PDF_ID:-}"
[[ -n "$TYPE" ]] || fatal "GED_TYPE_DOCUMENT_PDF_ID requis : identifiant d'un type de document n'acceptant que le PDF"

depot_accepte E5-R01 "Témoin : vrai PDF accepté" "$DONNEES/pdf_texte_fr_convention.pdf" "temoin-type.pdf" "$TYPE"
depot_refuse E5-R02 "Texte brut sous extension .pdf : 415 FORMAT_NON_AUTORISE [6.1.5]" "$DONNEES/faux_pdf_texte.pdf" "faux-texte.pdf" "$TYPE" 415 FORMAT_NON_AUTORISE
depot_refuse E5-R03 "Exécutable sous extension .pdf : 415 [6.1.5]" "$DONNEES/faux_pdf_executable.pdf" "faux-executable.pdf" "$TYPE" 415 FORMAT_NON_AUTORISE
depot_refuse E5-R04 "DOCX sous extension .pdf : 415 [6.1.5]" "$DONNEES/document_fr.docx" "faux-docx.pdf" "$TYPE" 415 FORMAT_NON_AUTORISE
depot_refuse E5-R05 "PNG sous extension .pdf : 415 [6.1.5]" "$DONNEES/image_scan_fr.png" "faux-png.pdf" "$TYPE" 415 FORMAT_NON_AUTORISE

api_depot "$DONNEES/pdf_texte_fr_facture.pdf" "vrai-pdf.txt" "$TYPE"
if [[ -n "$DEPOT_ID" ]]; then DEPOSES+=("$DEPOT_ID"); resultat E5-R06 OK "Vrai PDF sous extension .txt accepté (le contenu fait foi)" "HTTP $HTTP_CODE"
else resultat E5-R06 AVERT "Vrai PDF sous extension .txt accepté (le contenu fait foi)" "refusé HTTP $HTTP_CODE $(code_metier) : plus strict que le DAT, à confirmer avec dev3"; fi
bilan "E5 type réel"
