#!/bin/bash
# Passe les lignes crypttab / fstab proposées dans EXPLOITATION.md §11 aux
# générateurs systemd réels (systemd 255), sans toucher à /etc : SYSTEMD_CRYPTTAB
# et SYSTEMD_FSTAB désignent des fichiers d'essai.
D=$(mktemp -d); trap 'rm -rf "$D"' EXIT
rm -rf "$D/gen-tpm" "$D/gen-tang"; mkdir -p "$D/gen-tpm" "$D/gen-tang"
UUID=0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0

cat > "$D/crypttab-tpm" <<EOF
pgdata UUID=$UUID none luks,discard,tpm2-device=auto
EOF
cat > "$D/crypttab-tang" <<EOF
pgdata UUID=$UUID none luks,discard,_netdev
EOF
cat > "$D/fstab-tpm" <<EOF
/dev/mapper/pgdata /var/lib/pgsql xfs defaults,noatime 0 2
EOF
cat > "$D/fstab-tang" <<EOF
/dev/mapper/pgdata /var/lib/pgsql xfs defaults,noatime,_netdev 0 2
EOF

for v in tpm tang; do
  echo "===== variante $v"
  SYSTEMD_CRYPTTAB="$D/crypttab-$v" /usr/lib/systemd/system-generators/systemd-cryptsetup-generator "$D/gen-$v" "$D/gen-$v" "$D/gen-$v"
  SYSTEMD_FSTAB="$D/fstab-$v" SYSTEMD_PROC_CMDLINE="" /usr/lib/systemd/system-generators/systemd-fstab-generator "$D/gen-$v" "$D/gen-$v" "$D/gen-$v"
  echo "--- unité produite pour le volume"
  grep -E "^(After|Before|Wants|Requires|BindsTo|ExecStart|DefaultDependencies)" "$D/gen-$v/systemd-cryptsetup@pgdata.service"
  echo "--- cible qui ouvre le volume"
  find "$D/gen-$v" -path "*cryptsetup.target.requires*" -o -path "*cryptsetup.target.wants*" | sed "s|$D/gen-$v/||"
  echo "--- montage"
  grep -E "^(After|Before|Requires|What|Where|Options)" "$D/gen-$v/var-lib-pgsql.mount"
  find "$D/gen-$v" -name "var-lib-pgsql.mount" -path "*fs.target*" | sed "s|$D/gen-$v/||"
done
