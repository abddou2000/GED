#!/usr/bin/env bash
# =====================================================================
#  sauvegarder-cles.sh — sauvegarde chiffrée des clés, SÉPARÉE des fichiers
#  (DAT 6.1.3, 6.5)
#
#  Contenu de l'archive : le keystore PKCS#12 des clés maîtresses (KEK), la
#  table cle_fichier (clés de données enveloppées) et les fichiers de secrets
#  de l'environnement (dont le mot de passe du keystore). Sans ces éléments,
#  la sauvegarde des fichiers chiffrés est inexploitable.
#
#  L'archive est chiffrée par GPG pour la clé PUBLIQUE du responsable
#  sécurité de MMED : le serveur peut la produire mais pas la relire. Elle est
#  déposée sur un support distinct de celui des fichiers.
#
#  Fréquence (DAT 6.5) : quotidienne pour la table ; et à chaque rotation de
#  KEK, qui modifie le keystore — lancer alors ce script à la main.
# =====================================================================
set -Eeuo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/commun-sauvegarde.sh"

[[ -n "$DEST_CLES" ]] || echec "GED_SAUVEGARDE_CLES_DESTINATION absente"
case "$(readlink -f "$DEST_CLES")/" in
    "$(readlink -f "$GED_SAUVEGARDE_DESTINATION")"/*) echec "les clés ne doivent pas être sauvegardées avec les fichiers" ;;
esac
: "${GED_SAUVEGARDE_GPG_DESTINATAIRE:?destinataire GPG absent : les clés ne sont jamais sauvegardées en clair}"
: "${GED_KEYSTORE_CHEMIN:?GED_KEYSTORE_CHEMIN absent}"
[[ -f "$GED_KEYSTORE_CHEMIN" ]] || echec "keystore introuvable : $GED_KEYSTORE_CHEMIN"
exiger_commandes gpg tar pg_dump psql

TRAVAIL="$(mktemp -d)"
chmod 0700 "$TRAVAIL"
# Le contenu en clair ne survit pas au script, même en cas d'échec.
trap 'rm -rf "$TRAVAIL"' EXIT

mkdir -p "$TRAVAIL/cles"
cp -p "$GED_KEYSTORE_CHEMIN" "$TRAVAIL/cles/"
for secret in ${GED_SAUVEGARDE_SECRETS:-}; do
    [[ -f "$secret" ]] && cp -p "$secret" "$TRAVAIL/cles/"
done

schema="${GED_SCHEMA:-ged}"
if [[ "$(psql -XAtc "select to_regclass('$schema.cle_fichier') is not null")" == "t" ]]; then
    pg_dump --format=custom --table="$schema.cle_fichier" --file="$TRAVAIL/cles/cle_fichier.dump" \
        || echec "export de cle_fichier en échec"
    psql -XAtc "select count(*) from $schema.cle_fichier" > "$TRAVAIL/cles/cle_fichier.nombre"
else
    journal "ATTENTION : table $schema.cle_fichier absente (stockage chiffré pas encore migré)"
fi
(cd "$TRAVAIL/cles" && sha256sum -- * > EMPREINTES)

mkdir -p "$DEST_CLES"
chmod 0700 "$DEST_CLES"
ARCHIVE="$DEST_CLES/$(horodatage)-cles.tar.gpg"
tar -C "$TRAVAIL" -cf - cles \
    | gpg --batch --yes --trust-model always --encrypt --recipient "$GED_SAUVEGARDE_GPG_DESTINATAIRE" \
          --output "$ARCHIVE.partiel" \
    || echec "chiffrement de l'archive des clés en échec"
mv "$ARCHIVE.partiel" "$ARCHIVE"
sha256sum "$ARCHIVE" > "$ARCHIVE.sha256"
journal "Clés sauvegardées : $ARCHIVE ($(wc -l < "$TRAVAIL/cles/EMPREINTES") éléments)"

# Rétention : « durée de vie des fichiers concernés » (DAT 6.5). Une KEK
# retirée peut encore protéger des fichiers sauvegardés : on ne supprime
# aucune archive de clés automatiquement.
