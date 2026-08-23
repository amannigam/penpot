#!/usr/bin/env bash
# Back up the production Gridline instance: Postgres dump + assets tarball.
#
#   gridline/scripts/backup.sh [ssh-host] [local-dest-dir]
#
# Penpot state is exactly two things — the database and the assets volume.
# Restoring both onto a fresh stack of the same image version reproduces it.
set -euo pipefail

cd "$(dirname "$0")/../.."
[[ -f gridline/scripts/homelab.env ]] && source gridline/scripts/homelab.env

HOST="${1:-${GRIDLINE_HOST:-homelab}}"
DEST="${2:-${GRIDLINE_BACKUP_DIR:-$HOME/backups/gridline}}"
PROJECT="${GRIDLINE_PROJECT:-gridline}"
STAMP=$(date +%Y%m%d-%H%M%S)

mkdir -p "$DEST"
info() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }

info "Dumping Postgres from $HOST"
ssh "$HOST" "docker exec -i ${PROJECT}-penpot-postgres-1 pg_dump -U penpot -Fc penpot" \
  > "$DEST/penpot-db-$STAMP.dump"

info "Archiving assets volume from $HOST"
ssh "$HOST" "docker run --rm -v ${PROJECT}_gridline_assets:/assets:ro alpine tar -C /assets -cz ." \
  > "$DEST/penpot-assets-$STAMP.tar.gz"

info "Wrote:"
ls -lh "$DEST/penpot-db-$STAMP.dump" "$DEST/penpot-assets-$STAMP.tar.gz"

cat <<EOF

Restore (onto a stack running the SAME image version):
  cat penpot-db-$STAMP.dump | ssh $HOST 'docker exec -i ${PROJECT}-penpot-postgres-1 pg_restore -U penpot -d penpot --clean --if-exists'
  cat penpot-assets-$STAMP.tar.gz | ssh $HOST 'docker run --rm -i -v ${PROJECT}_gridline_assets:/assets alpine tar -C /assets -xz'
EOF
