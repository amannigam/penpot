#!/usr/bin/env bash
# Wipe every account except the ones we keep, so provider sign-up can be
# re-tested from scratch. Intended for the test phase only -- it hard-deletes
# profiles, their teams, projects and files.
#
#   gridline/scripts/reset-test-users.sh            # dry run: list what would go
#   gridline/scripts/reset-test-users.sh --yes      # actually delete
#   KEEP="a@x.com,b@y.com" gridline/scripts/reset-test-users.sh --yes
#
# Notes on why the SQL looks the way it does:
#  - Penpot guards its tables with a raise_deletion_protection() trigger,
#    because the app soft-deletes and a GC worker hard-deletes later. We turn
#    it off for the transaction only (SET LOCAL).
#  - Order matters. profile.default_team_id and profile.default_project_id are
#    NO ACTION, so profiles must go before their teams and projects.
set -euo pipefail

cd "$(dirname "$0")/../.."
[[ -f gridline/scripts/homelab.env ]] && source gridline/scripts/homelab.env

HOST="${GRIDLINE_HOST:-homelab}"
PROJECT="${GRIDLINE_PROJECT:-gridline}"
PG="${PROJECT}-penpot-postgres-1"
KEEP="${KEEP:-aman@saarstudios.com}"

# 'a@x.com,b@y.com' -> "'a@x.com','b@y.com'"
KEEP_SQL=$(printf "%s" "$KEEP" | awk -F, '{for(i=1;i<=NF;i++){gsub(/^ +| +$/,"",$i); printf "%s'\''%s'\''", (i>1?",":""), $i}}')

echo "==> Host: $HOST   keeping: $KEEP"

ssh "$HOST" "docker exec -i $PG psql -U penpot -d penpot -t -A -F'|' -c \
  \"select email, is_active, auth_backend from profile where email not in ($KEEP_SQL);\"" \
  | sed 's/^/    would delete: /'

if [[ "${1:-}" != "--yes" ]]; then
  echo "==> Dry run. Re-run with --yes to delete."
  exit 0
fi

ssh "$HOST" "docker exec -i $PG psql -U penpot -d penpot -v ON_ERROR_STOP=1" <<SQL
begin;
set local rules.deletion_protection to off;

delete from profile where email not in ($KEEP_SQL);

-- Teams whose last member just went away take their projects and files with
-- them. Teams that still have members are left completely alone.
create temporary table _dead_teams on commit drop as
  select t.id from team t
  where not exists (select 1 from team_profile_rel r where r.team_id = t.id);

delete from file    where project_id in (select p.id from project p join _dead_teams d on d.id = p.team_id);
delete from project where team_id    in (select id from _dead_teams);
delete from team    where id         in (select id from _dead_teams);

commit;
SQL

echo "==> Remaining profiles:"
ssh "$HOST" "docker exec -i $PG psql -U penpot -d penpot -t -A -F'|' -c \
  \"select email, is_active, auth_backend from profile order by created_at;\"" | sed 's/^/    /'
echo "==> Orphan teams (should be 0):"
ssh "$HOST" "docker exec -i $PG psql -U penpot -d penpot -t -A -c \
  \"select count(*) from team t where not exists (select 1 from team_profile_rel r where r.team_id=t.id);\"" | sed 's/^/    /'
