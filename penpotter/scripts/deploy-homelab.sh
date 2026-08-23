#!/usr/bin/env bash
# Deploy the production stack to the home lab.
#
#   penpotter/scripts/deploy-homelab.sh [ssh-host] [remote-dir]
#
# Defaults come from penpotter/scripts/homelab.env if present (gitignored).
# The server pulls images from GHCR — nothing is built here.
#
# First run: the script copies .env.example across if no .env exists yet and
# stops, so you can fill in secrets on the server before anything starts.
set -euo pipefail

cd "$(dirname "$0")/../.."
[[ -f penpotter/scripts/homelab.env ]] && source penpotter/scripts/homelab.env

HOST="${1:-${PENPOTTER_HOST:-homelab}}"
DIR="${2:-${PENPOTTER_DIR:-/opt/penpotter}}"
PROJECT="${PENPOTTER_PROJECT:-penpotter}"

info() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31mxx\033[0m %s\n' "$*" >&2; exit 1; }

info "Target: $HOST:$DIR"
ssh -o ConnectTimeout=10 "$HOST" true || die "Cannot reach $HOST over SSH."

ssh "$HOST" "command -v docker >/dev/null && docker compose version >/dev/null" \
  || die "Docker + compose plugin are not installed on $HOST."

ssh "$HOST" "mkdir -p '$DIR'"

info "Syncing stack definition (.env on the server is never overwritten)"
rsync -az --delete \
  --exclude '.env' \
  penpotter/prod/ "$HOST:$DIR/"

if ! ssh "$HOST" "test -f '$DIR/.env'"; then
  info "No .env on the server yet — seeding from .env.example."
  ssh "$HOST" "cp '$DIR/.env.example' '$DIR/.env' && chmod 600 '$DIR/.env'"
  cat <<EOF

  Stopping here. Fill in the secrets on the server, then re-run this script:

    ssh $HOST
    sudo -e $DIR/.env      # PENPOT_SECRET_KEY, POSTGRES_PASSWORD, SMTP, PENPOT_PUBLIC_URI

  Generate the keys with:
    python3 -c "import secrets; print(secrets.token_urlsafe(64))"   # PENPOT_SECRET_KEY
    openssl rand -hex 24                                            # POSTGRES_PASSWORD
EOF
  exit 0
fi

info "Pulling images and starting"
ssh "$HOST" "cd '$DIR' && docker compose -p '$PROJECT' --env-file .env -f docker-compose.yaml pull \
  && docker compose -p '$PROJECT' --env-file .env -f docker-compose.yaml up -d --remove-orphans"

info "Status"
ssh "$HOST" "cd '$DIR' && docker compose -p '$PROJECT' ps"
info "Done. Logs:  ssh $HOST 'cd $DIR && docker compose -p $PROJECT logs -f penpot-backend'"
