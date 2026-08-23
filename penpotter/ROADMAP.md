# Penpotter roadmap

What we're building on top of upstream Penpot. Ordered by when we need it.

---

## 1. Onboarding: profile, team, and Figma import in one flow

**Goal.** When someone joins the instance, the setup they walk through should end
with their Figma work already inside Penpotter — not with an empty dashboard and
a wiki page explaining how to migrate.

Today those are three disconnected things:

1. Penpot's signup → profile → team creation flow (upstream).
2. [`penpot-exporter-figma-plugin`](https://github.com/penpot/penpot-exporter-figma-plugin) —
   a Figma plugin, installed manually from `manifest.json` via Figma's dev menu,
   that walks a Figma file and emits a `.zip`.
3. Penpot's "import file" action in the projects menu, which accepts that `.zip`.

**What we want to change.**

- Extend the post-signup wizard with an explicit *Bring your Figma files* step,
  after team creation and before the dashboard.
- Host our own build of the exporter plugin and serve install instructions (and
  the `manifest.json`) from our instance, so nobody has to find the GitHub repo.
- Make the import step a first-class drop target that accepts multiple exporter
  `.zip`s at once and reports per-file results, rather than the generic
  one-file-at-a-time import.
- Make the step skippable and re-runnable later from the dashboard — people join
  after the initial migration too.

**Known constraints to design around** (documented by the plugin itself):

- Export is per-Figma-file and driven by a human clicking in Figma. There is no
  bulk/API path; Figma does not expose `.fig` or plugin runs to automation.
- Large files are slow and can fail, due to Figma plugin API limits.
- Prototyping interactions and flows are not converted.
- Fidelity is approximate where Figma features have no Penpot equivalent.

So the flow must be honest: show what converted, what didn't, and let people
re-import a file after fixing it, without duplicating projects.

**Where this touches the code.** `frontend/src/app/main/ui/onboarding/`, the
dashboard import path, and `backend` file-import RPC. Keep our changes additive
and behind a flag (`enable-penpotter-onboarding`) so upstream merges stay clean.

---

## 2. Fork hygiene

- Keep all our code under `penpotter/` and clearly-marked flags. Every edit to an
  upstream file is a future merge conflict; prefer new files and extension points.
- `sync-upstream.sh` runs against upstream `main` (stable), not `develop`.

## 3. Later

- SSO for the team (Penpot supports `enable-login-with-oidc`) so onboarding
  doesn't need passwords at all.
- Move assets from the filesystem volume to S3-compatible storage on the home lab
  (`PENPOT_OBJECTS_STORAGE_BACKEND=s3`) once the assets volume gets unwieldy.
- Automated nightly backup + restore drill, instead of the manual `backup.sh`.
