#!/usr/bin/env bash
# Recette E5 — « Un fichier altéré est détecté » (critère de sortie E5 ; DAT §6.1.2 GCM,
# §6.1.4 empreinte SHA-256 et vérification à la demande).
#
# À exécuter SUR LE SERVEUR du stockage (accès en écriture à la racine : compte de service
# ou root), sur UAT uniquement. Chaque altération est défaite aussitôt après sa mesure
# (copie de sauvegarde) : le document de test retrouve son état initial.
#   A01 dépôt puis téléchargement : octets identiques (référence) ;
#   A02 le fichier stocké du document est identifié (seul fichier créé par le dépôt) ;
#   A03 un octet inversé au milieu du chiffré → téléchargement refusé, aucun clair altéré servi ;
#   A04 fichier tronqué de 10 octets → refusé ;
#   A05 fichier remplacé par le chiffré d'un AUTRE document (même clé impossible, même
#       taille de format) → refusé (identifiant lié aux données authentifiées) ;
#   A06 grand fichier (3 segments de 1 Mio) altéré dans son dernier segment → le transfert
#       n'aboutit pas à un fichier complet (aucun octet non authentifié livré) ;
#   A07 après restauration, téléchargement de nouveau identique (le refus venait bien de
#       l'altération) ;
#   A08 (GED_API_VERIF_INTEGRITE, gabarit avec {id}) la vérification à la demande signale
#       la divergence ; A09 (GED_API_AUDIT) événement d'audit d'intégrité.
# Usage : verifier-alteration.sh --url URL --racine STOCKAGE [--nettoyer]

source "$(dirname "${BASH_SOURCE[0]}")/commun-e5.sh" "$@"
[[ -n "$RACINE_STOCKAGE" && -d "$RACINE_STOCKAGE" ]] || fatal "--racine STOCKAGE obligatoire (le fichier chiffré doit être altéré sur disque)"
refuser_production "${GED_ENV:-$GED_URL}"
connexion_ou_abandon
TYPE="${GED_TYPE_DOCUMENT_PDF_ID:-${GED_TYPE_DOCUMENT_ID:-$(type_document_par_defaut)}}"
REF="$DONNEES/pdf_texte_fr_convention.pdf"

# Dépose et renvoie le chemin du SEUL fichier publié par ce dépôt.
deposer_et_localiser() {  # $1 fichier $2 nom → FICHIER_STOCKE, DOC_ID
  local marque="$API_TMP/marque.$RANDOM"; touch "$marque"; sleep 1
  api_depot "$1" "$2" "$TYPE"; DOC_ID="$DEPOT_ID"; [[ -n "$DOC_ID" ]] && DEPOSES+=("$DOC_ID")
  mapfile -t nouveaux < <(find "$RACINE_STOCKAGE" -type f ! -name '.*.tmp' -newer "$marque" 2>/dev/null)
  FICHIER_STOCKE=""; [[ ${#nouveaux[@]} -eq 1 ]] && FICHIER_STOCKE="${nouveaux[0]}"
  NB_NOUVEAUX=${#nouveaux[@]}
}
octet_inverse() {  # octet_inverse FICHIER POSITION : XOR 0x01 sur un octet, en place
  local v; v=$(od -An -tu1 -j "$2" -N1 "$1" | tr -d ' ')
  printf "$(printf '\\%03o' $(( v ^ 1 )))" | dd of="$1" bs=1 seek="$2" conv=notrunc status=none
}
telechargement_refuse() {  # telechargement_refuse ID LIBELLE DOC_ID SHA_ORIGINAL
  api_telecharger "$3" "$API_TMP/recu"; local sha; sha="$(sha256_de "$API_TMP/recu")"
  if [[ "$HTTP_CODE" == 200 && "$sha" != "$4" && -s "$API_TMP/recu" ]]; then
    resultat "$1" ECHEC "$2" "HTTP 200 et contenu ALTÉRÉ servi à l'utilisateur ($(wc -c < "$API_TMP/recu") octets)"
  elif [[ "$HTTP_CODE" == 200 && "$sha" == "$4" ]]; then
    resultat "$1" ECHEC "$2" "HTTP 200, contenu identique : l'altération n'a pas porté sur le fichier lu"
  elif [[ "$HTTP_CODE" =~ ^[45] ]]; then
    resultat "$1" OK "$2" "HTTP $HTTP_CODE $(code_metier)"
  else
    resultat "$1" OK "$2" "transfert interrompu (HTTP $HTTP_CODE, $(wc -c < "$API_TMP/recu") octets reçus, empreinte différente)"
  fi
}
avec_sauvegarde() { cp -p "$1" "$API_TMP/sauvegarde"; chmod u+w "$1" 2>/dev/null; }
restaurer() { cp "$API_TMP/sauvegarde" "$1"; touch -r "$API_TMP/sauvegarde" "$1"; }

SHA_REF="$(sha256_de "$REF")"
deposer_et_localiser "$REF" "alteration-$RANDOM.pdf"
[[ -n "$DOC_ID" ]] || fatal "dépôt de référence refusé : HTTP $HTTP_CODE $(code_metier)"
api_telecharger "$DOC_ID" "$API_TMP/recu"
[[ "$HTTP_CODE" == 200 && "$(sha256_de "$API_TMP/recu")" == "$SHA_REF" ]] \
  && resultat E5-A01 OK "Référence : dépôt puis téléchargement identique" "id $DOC_ID" \
  || resultat E5-A01 ECHEC "Référence : dépôt puis téléchargement identique" "HTTP $HTTP_CODE"
if [[ -z "$FICHIER_STOCKE" ]]; then
  resultat E5-A02 ECHEC "Fichier stocké du document identifié" "$NB_NOUVEAUX fichier(s) créé(s) par le dépôt (1 attendu) — dépôts concurrents ?"
  bilan "E5 altération"; exit 1
fi
resultat E5-A02 OK "Fichier stocké du document identifié" "${FICHIER_STOCKE#"$RACINE_STOCKAGE"/} ($(wc -c < "$FICHIER_STOCKE") octets)"
F="$FICHIER_STOCKE"; TAILLE=$(wc -c < "$F")

avec_sauvegarde "$F"; octet_inverse "$F" $((TAILLE / 2))
telechargement_refuse E5-A03 "Octet inversé dans le chiffré : lecture refusée [6.1.2]" "$DOC_ID" "$SHA_REF"
if [[ -n "${GED_API_VERIF_INTEGRITE:-}" ]]; then
  api_appel POST "$(gabarit "$GED_API_VERIF_INTEGRITE" id "$DOC_ID")"
  if [[ "$HTTP_CODE" =~ ^[45] ]] || grep -qi 'ALTERE\|DIVERGEN\|INTEGRITE_COMPROMISE\|"valide":false\|"integre":false' "$HTTP_CORPS"; then
    resultat E5-A08 OK "Vérification d'intégrité à la demande : divergence signalée [6.1.4]" "HTTP $HTTP_CODE"
  else
    resultat E5-A08 ECHEC "Vérification d'intégrité à la demande : divergence signalée [6.1.4]" "HTTP $HTTP_CODE $(head -c 200 "$HTTP_CORPS")"
  fi
else
  resultat E5-A08 NA "Vérification d'intégrité à la demande" "GED_API_VERIF_INTEGRITE non défini (point d'entrée à fixer avec dev3)"
fi
restaurer "$F"

avec_sauvegarde "$F"; truncate -s $((TAILLE - 10)) "$F"
telechargement_refuse E5-A04 "Fichier tronqué : lecture refusée [6.1.2]" "$DOC_ID" "$SHA_REF"
restaurer "$F"

FICHIER_A="$F"; DOC_A="$DOC_ID"
deposer_et_localiser "$DONNEES/pdf_texte_fr_facture.pdf" "substitution-$RANDOM.pdf"
if [[ -n "$FICHIER_STOCKE" ]]; then
  avec_sauvegarde "$FICHIER_A"; cp "$FICHIER_STOCKE" "$FICHIER_A"
  telechargement_refuse E5-A05 "Chiffré d'un autre document substitué : lecture refusée [6.1.2]" "$DOC_A" "$SHA_REF"
  restaurer "$FICHIER_A"
else
  resultat E5-A05 ECHEC "Chiffré d'un autre document substitué : lecture refusée" "second dépôt non localisé (HTTP $HTTP_CODE)"
fi

# Grand fichier : 3 Mio de PDF valide (tête %PDF-) complété d'octets aléatoires.
GRAND="$API_TMP/grand.pdf"; { cat "$REF"; head -c $((3 * 1024 * 1024)) /dev/urandom; } > "$GRAND"
SHA_GRAND="$(sha256_de "$GRAND")"
deposer_et_localiser "$GRAND" "grand-$RANDOM.pdf"
if [[ -n "$FICHIER_STOCKE" ]]; then
  G="$FICHIER_STOCKE"; avec_sauvegarde "$G"; octet_inverse "$G" $(( $(wc -c < "$G") - 100 ))
  telechargement_refuse E5-A06 "Grand fichier altéré en fin : aucun fichier complet livré [6.1.2]" "$DOC_ID" "$SHA_GRAND"
  restaurer "$G"
else
  resultat E5-A06 ECHEC "Grand fichier altéré en fin" "dépôt de 3 Mio non localisé (HTTP $HTTP_CODE $(code_metier))"
fi

api_telecharger "$DOC_A" "$API_TMP/recu"
[[ "$HTTP_CODE" == 200 && "$(sha256_de "$API_TMP/recu")" == "$SHA_REF" ]] \
  && resultat E5-A07 OK "Après restauration : téléchargement de nouveau identique" \
  || resultat E5-A07 ECHEC "Après restauration : téléchargement de nouveau identique" "HTTP $HTTP_CODE"

if [[ -n "${GED_API_AUDIT:-}" ]]; then
  api_appel GET "$GED_API_AUDIT"
  grep -q 'INTEGRITE' "$HTTP_CORPS" && resultat E5-A09 OK "Altération auditée [6.1.4, 7.4.1]" \
                                    || resultat E5-A09 ECHEC "Altération auditée [6.1.4, 7.4.1]" "aucun événement INTEGRITE dans $GED_API_AUDIT"
else
  resultat E5-A09 NA "Altération auditée" "journal d'audit livré en E4 (GED_API_AUDIT)"
fi
bilan "E5 altération"
