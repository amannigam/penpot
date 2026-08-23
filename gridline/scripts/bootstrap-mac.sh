#!/usr/bin/env bash
# Prepare this Mac to run and build Gridline.
#
#   gridline/scripts/bootstrap-mac.sh          # check only
#   gridline/scripts/bootstrap-mac.sh --install # install what's missing
#
# Uses Colima rather than Docker Desktop: no GUI, no login, no background
# updater, and the VM disk is capped so a runaway build can't eat the host.
set -euo pipefail

INSTALL=0
[[ "${1:-}" == "--install" ]] && INSTALL=1

# Colima VM sizing. The devenv build is memory hungry; the release stack is not.
COLIMA_PROFILE="${COLIMA_PROFILE:-gridline}"
COLIMA_CPU="${COLIMA_CPU:-4}"
COLIMA_MEMORY="${COLIMA_MEMORY:-8}"   # GB
COLIMA_DISK="${COLIMA_DISK:-40}"      # GB, sparse — only what's used is on disk

info() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31mxx\033[0m %s\n' "$*" >&2; exit 1; }

# --- disk guard --------------------------------------------------------------
avail_gb=$(df -g / | awk 'NR==2 {print $4}')
info "Free disk: ${avail_gb}GB"
if (( avail_gb < 25 )); then
  warn "Penpot images + Postgres + the Colima VM want ~25GB free; you have ${avail_gb}GB."
  warn "Free up space before running the devenv build, or it will fail mid-way."
  (( INSTALL )) && die "Refusing to install with <25GB free. Re-run after freeing space, or set FORCE=1."
fi
[[ "${FORCE:-0}" == "1" ]] && warn "FORCE=1 set, ignoring disk guard."

# --- tooling -----------------------------------------------------------------
need_install=()
command -v brew   >/dev/null || die "Homebrew is required: https://brew.sh"
command -v colima >/dev/null || need_install+=(colima)
command -v docker >/dev/null || need_install+=(docker)
docker compose version >/dev/null 2>&1 || need_install+=(docker-compose)

if (( ${#need_install[@]} )); then
  info "Missing: ${need_install[*]}"
  if (( INSTALL )); then
    brew install "${need_install[@]}"
    # docker-compose from brew installs as a CLI plugin; wire it up for the
    # `docker compose` subcommand form.
    mkdir -p ~/.docker/cli-plugins
    ln -sfn "$(brew --prefix)/opt/docker-compose/bin/docker-compose" ~/.docker/cli-plugins/docker-compose
  else
    info "Re-run with --install to install them."
    exit 0
  fi
fi

# --- colima VM ---------------------------------------------------------------
if colima status -p "$COLIMA_PROFILE" >/dev/null 2>&1; then
  info "Colima profile '$COLIMA_PROFILE' already running."
else
  if (( INSTALL )); then
    info "Starting Colima ($COLIMA_CPU cpu / ${COLIMA_MEMORY}GB ram / ${COLIMA_DISK}GB disk)"
    colima start -p "$COLIMA_PROFILE" \
      --cpu "$COLIMA_CPU" --memory "$COLIMA_MEMORY" --disk "$COLIMA_DISK" \
      --vm-type=vz --vz-rosetta
  else
    info "Colima profile '$COLIMA_PROFILE' not running. Start it with:"
    echo "  colima start -p $COLIMA_PROFILE --cpu $COLIMA_CPU --memory $COLIMA_MEMORY --disk $COLIMA_DISK --vm-type=vz --vz-rosetta"
    exit 0
  fi
fi

docker version >/dev/null || die "Docker CLI cannot reach the Colima daemon."
info "Docker is ready. Next: gridline/dev/ — cp .env.example .env, then"
echo "  docker compose -p gridline-dev --env-file gridline/dev/.env -f gridline/dev/docker-compose.yaml up -d"
