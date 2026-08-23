#!/usr/bin/env bash
# Pull upstream Penpot changes into our fork.
#
#   gridline/scripts/sync-upstream.sh          # fetch + report what's new
#   gridline/scripts/sync-upstream.sh --merge  # also merge upstream/main into gridline
#
# We track upstream `main` (the stable release line), not `develop`. Our own
# work lives on the `gridline` branch; everything we add is confined to
# gridline/ and .github/workflows/gridline-*.yml so merges stay boring.
set -euo pipefail
cd "$(dirname "$0")/../.."

info() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }

git remote get-url upstream >/dev/null 2>&1 \
  || git remote add upstream https://github.com/penpot/penpot.git

info "Fetching upstream"
git fetch upstream main --tags

BASE=$(git merge-base HEAD upstream/main)
AHEAD=$(git rev-list --count "$BASE"..upstream/main)
info "upstream/main is $AHEAD commit(s) ahead of our merge base"

if (( AHEAD == 0 )); then
  info "Already up to date."
  exit 0
fi

git --no-pager log --oneline "$BASE"..upstream/main | head -40
echo "..."

if [[ "${1:-}" == "--merge" ]]; then
  [[ -z "$(git status --porcelain)" ]] || { echo "Working tree is dirty; commit or stash first." >&2; exit 1; }
  info "Merging upstream/main into $(git branch --show-current)"
  git merge --no-edit upstream/main
  info "Merged. Push and let CI rebuild the images."
else
  info "Re-run with --merge to merge."
fi
