# Penpotter

Our internal fork of [Penpot](https://github.com/penpot/penpot) — a self-hosted
Figma replacement that we also extend as a product.

Two instances, one codebase:

| | Where | Runs | Purpose |
|---|---|---|---|
| **dev** | this Mac | `penpotter/dev/` compose, or the hot-reload devenv | Build and try changes |
| **prod** | home lab | `penpotter/prod/` compose, GHCR images | The instance the team actually designs in |

Everything we add lives under `penpotter/` and `.github/workflows/penpotter-*.yml`
so that merging upstream Penpot stays a non-event. See `ROADMAP.md` for what we
are building on top.

---

## Repository layout

```
penpotter/
  dev/          local release-shaped stack (compose + .env.example)
  prod/         home lab stack (compose + Caddyfile + .env.example)
  scripts/      bootstrap-mac.sh, deploy-homelab.sh, backup.sh, sync-upstream.sh
  ROADMAP.md    product extensions we're planning
```

Branches:

- `main` — clean mirror of upstream `penpot/penpot@main` (the stable line). Never commit here.
- `penpotter` — our line. Pushes here build `:dev` images.
- tags `v*` — production releases. Build `:release` + `:vX.Y.Z` images.

---

## 1. Prepare the Mac

```bash
penpotter/scripts/bootstrap-mac.sh            # check what's missing
penpotter/scripts/bootstrap-mac.sh --install  # install colima + docker, start the VM
```

The script refuses to install with under 25 GB free. Penpot's dev toolchain is
large: the `penpotapp/devenv` image alone is several GB, plus the JVM/CLJS build
caches and Postgres data.

We use **Colima**, not Docker Desktop — no GUI, no account, and the VM disk is
capped so a runaway build can't fill the host.

## 2. Run the dev stack

This runs release-shaped containers on your laptop. Good for smoke-testing and
for using Penpot locally; it is *not* the hot-reload coding loop.

```bash
cd penpotter/dev
cp .env.example .env
python3 -c "import secrets; print('PENPOT_SECRET_KEY='+secrets.token_urlsafe(64))" >> .env
docker compose -p penpotter-dev --env-file .env -f docker-compose.yaml up -d
open http://localhost:9101          # mail at http://localhost:9180
```

By default it pulls upstream `penpotapp/*:2.17` images (multi-arch, works on
Apple silicon). Flip `IMAGE_PREFIX`/`PENPOT_VERSION` in `.env` to run our own
`ghcr.io/amannigam/penpotter-*:dev` images instead.

Ports are offset (9101/9180) so this can run alongside anything on 9001.

## 3. The actual coding loop

For editing Penpot's source with hot reload, use upstream's devenv:

```bash
./manage.sh pull-devenv     # or build-devenv
./manage.sh run-devenv      # tmux session; frontend on http://localhost:3449
```

Read `docs/` and `AGENTS.md` in the repo root for how the devenv panes are laid
out. This is heavy — budget disk and RAM accordingly.

## 4. Ship to production

Images are built by GitHub Actions, never on the Mac:

- push to `penpotter` → `ghcr.io/amannigam/penpotter-{frontend,backend,exporter,mcp}:dev`
- push a `v*` tag → the same images tagged `:release` and `:vX.Y.Z`

The workflow builds the Penpot bundles inside `penpotapp/devenv`, then builds one
image per component. It needs two repository secrets, because Penpot's
Dockerfiles now build `FROM dhi.io` (Docker Hardened Images) which requires an
authenticated pull:

- `DOCKERHUB_USERNAME`
- `DOCKERHUB_TOKEN` (a free Docker Hub account + access token is enough)

Then deploy:

```bash
penpotter/scripts/deploy-homelab.sh            # uses the `homelab` ssh host
```

First run seeds `/home/claw/penpotter/.env` from the example and stops so you can fill
in secrets on the server. Subsequent runs rsync the stack, `docker compose pull`,
and `up -d`. The server's `.env` is never overwritten.

Cut a release:

```bash
git tag -a v0.1.0 -m "penpotter 0.1.0" && git push origin v0.1.0
# wait for CI, then:
ssh homelab "sed -i 's/^PENPOT_VERSION=.*/PENPOT_VERSION=v0.1.0/' /home/claw/penpotter/.env"
penpotter/scripts/deploy-homelab.sh
```

Pin `PENPOT_VERSION` to a `vX.Y.Z` tag in production rather than `release`, so a
rollback is a one-line edit.

## 5. Backups

```bash
penpotter/scripts/backup.sh
```

Penpot's entire state is the Postgres database plus the assets volume. The script
pulls both to `~/backups/penpotter` and prints the restore commands. Run it before
every upgrade — Penpot applies schema migrations on backend start and there is no
downgrade path.

## 6. Staying current with upstream

```bash
penpotter/scripts/sync-upstream.sh          # what's new
penpotter/scripts/sync-upstream.sh --merge  # merge upstream/main into penpotter
```

---

## Operational notes

**`PENPOT_PUBLIC_URI` must match the URL users type.** If it doesn't, assets 404
and the websocket connection fails — the editor loads but nothing syncs.

**Secure cookies vs plain HTTP.** Production defaults to secure session cookies.
If you serve the home lab over plain `http://` on the LAN, logins will silently
fail until you append `disable-secure-session-cookies` to `PENPOT_FLAGS`. Prefer
giving it real TLS (Tailscale HTTPS, or the `caddy` compose profile).

**Registration is disabled in production** (`disable-registration`). The first
account has to be created before you close it, or invited via SMTP. Bring people
in by invitation — which means SMTP must actually work.

**Resource sizing**, per Penpot's recommended settings: 4 CPU / 16 GB RAM serves
thousands of users; start Postgres at 50–100 GB with room to grow, roughly +5 GB
per additional editor. Valkey is capped at 128 MB with `volatile-lfu` eviction and
needs no disk.

**Postgres 15 is pinned.** Upstream ships `postgres:15`; do not bump the major
version without running `docker/postgres-upgrade.sh`.
