# Where to pick up

State as of 2026-08-24. Everything below is deployed and working on the home
lab unless marked otherwise.

## Working

- Fork at `amannigam/penpot`, branch `gridline`, based on upstream stable
- Production on the home lab: **https://lab.tail101716.ts.net:8443**
- Local dev instance: `gridline/dev/` (was running; images were reclaimed for
  disk, `docker compose -p gridline-dev ... up -d` brings it back)
- Devenv image pulled, hot-reload loop **never started** -- `./manage.sh run-devenv`
- Sign in with GitHub, including provider signups skipping email verification
- Gridline branding, logo, favicon, desktop app (Electron)
- Figma OAuth, file picker, and the importer

## Next up, in order

### 1. Components

Agreed to be done on its own, because it is relational rather than per-node
and a mistake there destabilises everything else.

What makes it harder than constraints or auto-layout:

- Penpot needs a **main instance** living on some page, with every `INSTANCE`
  pointing at it and overrides preserved.
- Figma's REST tree delivers instances **already expanded**, so the link has to
  be reconstructed rather than read. `componentId` on the instance and the
  top-level `components` map are the inputs.
- The plugin has a whole `transformOverrides.ts` for reconciling instance
  overrides against the main component. Read it first.

Reference files in penpot/penpot-exporter-figma-plugin:
`plugin-src/transformers/transformComponentNode.ts`,
`transformInstanceNode.ts`, `transformComponentSetNode.ts`,
`plugin-src/transformers/partials/transformOverrides.ts`,
`transformComponentNameAndPath.ts`, `transformVariantNameAndProperties.ts`.

Penpot side: `fb/add-component` in `common/src/app/common/files/builder.cljc`,
taking `{:component-id :file-id :page-id :frame-id :name :path :variant-id
:variant-properties}` with `:main-instance-id` set from the frame.

### 2. Remaining converter gaps

Ordered by likely visual impact: gradients (linear and radial map fairly
directly), grid layout, vector networks beyond flattened geometry, remaining
effects.

### 3. Real import progress

Currently a named step plus a per-file report. A true progress bar needs
either splitting the RPC into client-driven phases or pushing events over the
websocket. Penpot has no ready-made server->client progress channel -- checked.

### 4. The unverified-email banner

Still outstanding from early on. Password signups are blocked at "Check your
email"; providers are fine. Penpot's `is-active` conflates "verified" with
"may log in", so the cheap version is to create the profile active and carry
an unverified marker in profile props for a banner to read.

### 5. The tailnet relay

The biggest operational annoyance. The path from the Mac to the home lab
relays rather than connecting directly, so every image pull saturates the link
and takes the site down for the duration. Likely UDP 41641 blocked at the
router. Fixing it once makes every deploy uneventful.

## How to work on this

- **Run the converter, do not reason about it.** The devenv image is pulled;
  `docker run --rm -v "$(pwd)":/home/penpot/penpot -v gridline-m2:/root/.m2
  -w /home/penpot/penpot/backend --entrypoint bash penpotapp/devenv:latest -lc
  'clojure -M:dev -e "..."'` runs a namespace or the tests in about 30s. This
  caught real bugs every single time.
- **Read the plugin, not the REST docs.** The docs were wrong or misleading
  about scopes, endpoint versions, and several attribute mappings. The plugin
  is the working reference.
- **Deploys degrade the site** while images pull. Do them when nobody is using
  it, or accept a few minutes of wobble.
- CI fails on `:undeclared-var`, boots the backend image as a smoke test, and
  runs the converter tests. All three exist because something got through.
