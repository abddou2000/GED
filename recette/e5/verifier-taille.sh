#!/usr/bin/env bash
# Recette E5 — tailles maximales : 413 (DAT §6.1.5 : 100 Mo par défaut, paramétrable par type
# dans la limite de 200 Mo de la plateforme ; §5.3.2).
#
#   T01 fichier de 200 Mio + 1 octet → 413 (plafond de plateforme), rien d'écrit ;
#   T02 fichier de L + 1 octet sur un type limité à L → 413 FICHIER_TROP_VOLUMINEUX, rien d'écrit ;
#   T03 fichier d'exactement L octets sur ce type → accepté (borne incluse) ;
#   T04 (GED_TYPE_DOCUMENT_200_ID) fichier d'exactement 200 Mio sur un type à 200 Mo → accepté.
# Les fichiers sont des PDF valides (tête %PDF-) complétés par des octets nuls (fichiers
# creux) : leur type réel reste PDF, seul le critère de taille peut les refuser.
# Variables : GED_TYPE_DOCUMENT_PETIT_ID et GED_TYPE_DOCUMENT_PETIT_MAX_OCTETS (T02, T03).
# Derrière NGINX, le 413 de T01 peut venir de client_max_body_size (page HTML, sans code
# métier) : c'est conforme, le code métier n'est alors pas exigé.
# Usage : verifier-taille.sh --url URL [--racine STOCKAGE] [--nettoyer]

source "$(dirname "${BASH_SOURCE[0]}")/commun-e5.sh" "$@"
connexion_ou_abandon
PDF="$DONNEES/pdf_texte_fr_convention.pdf"
gonfler() { cp "$PDF" "$2"; truncate -s "$1" "$2"; }   # gonfler TAILLE SORTIE
MIO=$((1024 * 1024))

TYPE_DEFAUT="${GED_TYPE_DOCUMENT_ID:-$(type_document_par_defaut)}"
gonfler $((200 * MIO + 1)) "$API_TMP/plateforme.pdf"
avant="$(compter_stockes)"; api_depot "$API_TMP/plateforme.pdf" "trop-gros-200mo.pdf" "$TYPE_DEFAUT"; apres="$(compter_stockes)"
[[ -n "$DEPOT_ID" ]] && DEPOSES+=("$DEPOT_ID")
if [[ "$HTTP_CODE" == 413 && ( "$avant" == -1 || "$avant" == "$apres" ) ]]; then
  note=""; [[ "$avant" == -1 ]] && note=" (stockage non vérifié : --racine absent)"
  resultat E5-T01 OK "200 Mio + 1 octet refusé en 413 [6.1.5]" "code métier : $(code_metier)${note}"
else
  resultat E5-T01 ECHEC "200 Mio + 1 octet refusé en 413 [6.1.5]" "HTTP $HTTP_CODE $(code_metier) ; fichiers stockés $avant → $apres"
fi
rm -f "$API_TMP/plateforme.pdf"

if [[ -n "${GED_TYPE_DOCUMENT_PETIT_ID:-}" && -n "${GED_TYPE_DOCUMENT_PETIT_MAX_OCTETS:-}" ]]; then
  L="$GED_TYPE_DOCUMENT_PETIT_MAX_OCTETS"
  gonfler $((L + 1)) "$API_TMP/l-plus-1.pdf"
  depot_refuse E5-T02 "Limite du type dépassée d'un octet : 413 FICHIER_TROP_VOLUMINEUX [6.1.5]" "$API_TMP/l-plus-1.pdf" "limite-plus-un.pdf" "$GED_TYPE_DOCUMENT_PETIT_ID" 413 FICHIER_TROP_VOLUMINEUX
  gonfler "$L" "$API_TMP/l.pdf"
  depot_accepte E5-T03 "Taille exactement égale à la limite du type : acceptée" "$API_TMP/l.pdf" "limite-exacte.pdf" "$GED_TYPE_DOCUMENT_PETIT_ID"
else
  resultat E5-T02 NA "Limite propre au type de document" "définir GED_TYPE_DOCUMENT_PETIT_ID et GED_TYPE_DOCUMENT_PETIT_MAX_OCTETS"
  resultat E5-T03 NA "Borne incluse de la limite du type" "idem T02"
fi

if [[ -n "${GED_TYPE_DOCUMENT_200_ID:-}" ]]; then
  gonfler $((200 * MIO)) "$API_TMP/200.pdf"
  depot_accepte E5-T04 "Exactement 200 Mio accepté sur un type à 200 Mo [6.1.5]" "$API_TMP/200.pdf" "exactement-200mo.pdf" "$GED_TYPE_DOCUMENT_200_ID"
  rm -f "$API_TMP/200.pdf"
else
  resultat E5-T04 NA "Exactement 200 Mio accepté" "définir GED_TYPE_DOCUMENT_200_ID (type paramétré à 200 Mo)"
fi
bilan "E5 taille"
