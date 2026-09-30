#!/bin/bash
# Sauvegarde et restauration de l'en-tête LUKS2 sur un volume d'essai (fichier
# de 64 Mo), selon EXPLOITATION.md §11. Aucun disque réel n'est touché.
set -u
D=$(mktemp -d); trap 'rm -rf "$D"' EXIT
IMG="$D/volume.img"; HDR="$D/pgdata-luks-entete.img"
PHRASE="phrase-de-secours-essai"
rm -f "$IMG" "$HDR"
truncate -s 64M "$IMG"
echo "== formatage LUKS2 (argon2id, aes-xts-plain64, 512 bits)"
printf '%s' "$PHRASE" | cryptsetup luksFormat --batch-mode --type luks2 --cipher aes-xts-plain64 \
  --key-size 512 --pbkdf argon2id --pbkdf-memory 65536 --iter-time 200 --key-file - "$IMG" || exit 1
cryptsetup luksDump "$IMG" | grep -E "^Version|cipher:|Keyslots|PBKDF" | head -6
echo "== sauvegarde de l'en-tête"
cryptsetup luksHeaderBackup "$IMG" --header-backup-file "$HDR" || exit 1
ls -l "$HDR" | cut -d' ' -f5,9 ; sha256sum "$HDR" | cut -c1-16
echo "== ouverture de contrôle (phrase testée sans créer de périphérique)"
printf '%s' "$PHRASE" | cryptsetup open --test-passphrase --key-file - "$IMG" && echo "phrase OK"
echo "== en-tête détruit (16 premiers Mo mis à zéro, panne ou erreur de manipulation)"
dd if=/dev/zero of="$IMG" bs=1M count=16 conv=notrunc status=none
printf '%s' "$PHRASE" | cryptsetup open --test-passphrase --key-file - "$IMG" 2>&1 | head -1 || true
cryptsetup isLuks "$IMG" && echo "encore LUKS ?!" || echo "plus reconnu comme LUKS : données perdues sans sauvegarde d'en-tête"
echo "== restauration de l'en-tête"
cryptsetup luksHeaderRestore --batch-mode "$IMG" --header-backup-file "$HDR" || exit 1
printf '%s' "$PHRASE" | cryptsetup open --test-passphrase --key-file - "$IMG" && echo "phrase OK après restauration"
rm -f "$IMG"
