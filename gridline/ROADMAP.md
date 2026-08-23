# Gridline roadmap

What we're building on top of upstream Penpot. Ordered by when we need it.

---

## Known bugs to fix

### Flattening a text shape makes it disappear

Reported 2026-08-23 while producing the Gridline wordmark. Selecting a text
shape and flattening it (text → path) removes the shape from the canvas instead
of replacing it with its outlines.

This is not cosmetic: converting text to outlines is the normal way to ship a
logo or any type-as-artwork, and without it every SVG export carries a live
`<text>` node plus an `@font-face` pointing back at the instance that exported
it. Our own wordmark hit exactly that — the export referenced
`https://lab.tail101716.ts.net:8443/internal/gfonts/...`, so off the tailnet the
type fell back to a system font.

Worked around for the logo by outlining the glyphs offline against Sora 400
(the reconstructed advance width came to 408.40 against the file's recorded
`textLength` of 408.40, so the geometry is exact). That is not a fix; the
editor should do this.

Where to look: the flatten/`to-path` conversion path for text shapes, and
whether the render-wasm text editor is involved.

---

## 0. Sign-in: providers instead of passwords

**Goal.** Nobody types an email and password. They pick Google, GitHub (for the
developers), and ideally Apple, and they are in.

**Google + GitHub are config, not code.** Penpot supports both natively, and the
flag `oidc-registration` exists precisely for this shape: general registration
stays off, but signing in with a provider creates the account.

```
PENPOT_FLAGS=disable-registration enable-oidc-registration \
             disable-login-with-password \
             enable-login-with-google enable-login-with-github ...
PENPOT_REGISTRATION_DOMAIN_WHITELIST=saarstudios.com
```

`disable-login-with-password` hides the email/password form outright
(`frontend/src/app/main/ui/auth/login.cljs` gates it on that flag), so the login
screen becomes two buttons. The domain whitelist is what stops any random Google
account that can reach the tailnet from signing up.

All providers share **one** redirect URI — Penpot builds it as
`{PENPOT_PUBLIC_URI}/api/auth/oidc/callback` (`backend/src/app/auth/oidc.clj:411`)
— so both OAuth apps register the same callback.

**Apple is the one that needs real work.** There is no Apple provider in Penpot
(no mention anywhere in `backend/src/app/auth/`), and the generic OIDC provider
cannot stand in for it:

- Apple's `client_secret` is not a string but an ES256 JWT signed with a `.p8`
  key, valid for at most 6 months, so it has to be minted and rotated in code.
  Penpot's config takes a static `:oidc-client-secret` string.
- Apple returns the callback via `response_mode=form_post`, i.e. a **POST**.
  Penpot's callback route is declared `:allowed-methods #{:get}`
  (`oidc.clj:906`), so it would reject Apple outright.
- It requires a paid Apple Developer Program membership ($99/year).

So Apple = a real fork change (an Apple provider with JWT secret minting, plus a
POST-capable callback) *and* a recurring cost. Deferred until Google and GitHub
are proven and there is a budget.

**Verification is for passwords, not for providers.** A signup through Google,
GitHub or Apple has already had its identity established by the provider, so
Penpot has nothing left to verify and the user should land in the app. Email +
password signups keep mandatory verification.

Google already behaves this way — OIDC providers return the standard
`email_verified` claim and `auth.clj:449` creates the profile active when it is
true. GitHub did not, because of a dropped field: `/user/emails` reports
`verified` per address and `lookup-github-email` kept only the address. Fixed in
this fork by preserving the flag (`oidc.clj`), which also generalised the
`::get-email-fn` hook to return `{:email, :verified}` so any future provider can
report the same thing.

The subtlety that cost us a deploy: `/user` exposes a *public profile email*
when the account sets one, and upstream short-circuits on it without ever
calling `/user/emails`. The flag lives only on that second endpoint, so the
short-circuit meant "verified" was unknowable for exactly the accounts that
have a public email. We now always fetch the list and look the chosen address
up in it — an address GitHub lists as verified is verified regardless of which
endpoint surfaced it. Addresses absent from the list stay unverified.

Apple, when it lands, gets the same treatment: it authenticates the user, so no
verification mail.

**Still open: a banner for unverified accounts.** If someone does sign up with a
password, they are blocked at "Check your email!" until they click the link. On
an instance whose SMTP is a local sink, that is a dead end for anyone but an
admin. The intended behaviour is to let them into the app and show a persistent
"verify your email" banner instead. That needs `is-active` to stop meaning both
"verified" and "may log in" — the cheap version is to create the profile active
and carry an unverified marker in profile props for the banner to read, rather
than unpicking every `is-active` guard.

**Ordering note.** Do not set `disable-login-with-password` until a provider
login has actually worked once — the existing account was created with a
password, and turning the form off first locks everyone out. Penpot links a
provider identity to an existing profile by email, so sign in with the Google
account matching the existing address, confirm it lands in the same profile,
then disable passwords.

---

## 1. Onboarding: profile, team, and Figma import in one flow

**Goal.** When someone joins the instance, the setup they walk through should end
with their Figma work already inside Gridline — not with an empty dashboard and
a wiki page explaining how to migrate. Concretely: they sign in with a provider
(see #0), and the very next thing we ask is whether they have anything in Figma
to bring over — then we walk them through it rather than pointing at docs.

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

**Decided 2026-08-23: discovery is ours, conversion stays the plugin's.**

Figma's REST API can list files, so the picker is worth building:
`GET /v1/teams/:team_id/projects` then `GET /v1/projects/:project_id/files`.
Two constraints are surfaced in the UI rather than hidden — Figma's docs state
outright that "it is not possible to programmatically obtain team IDs" (so the
team URL is pasted), and drafts live outside team projects so they are not
listed.

Conversion is a different matter. The exporter's transformers are typed
against Figma's *plugin* API — `MinimalFillsMixin`, `VectorNode`,
`TextSegment` — and images come from `image.getBytesAsync()`, which REST has
no equivalent for (it returns an `imageRef` resolved through a separate
endpoint). Text styling, vector geometry and style ids are all modelled
differently too. Reusable from that repo: `ui-src/lib`, the `.penpot` builder,
which is input-agnostic and MPL-2.0 like Penpot. Not reusable:
`plugin-src/transformers`, ~20 transformers and ~30 translators.

So a REST converter is weeks of work that starts behind the plugin on fidelity
and then chases it forever. Rejected for now. Figma's MCP server is not an
alternative either: it is built for code generation from a selection, not a
fidelity-preserving representation.

Also worth knowing: a Figma plugin can only see the file it is open in, so
there is no bulk mode to add to the plugin either. The per-file run is Figma's
constraint, not the plugin's.

**Built.** `app.main.data.gridline.figma` (browser-side REST client; Figma
serves `access-control-allow-origin: *` and allows `X-Figma-Token`, so the
token never reaches our backend and is never stored) and
`app.main.ui.onboarding.figma-import` (intro → connect → checklist, with
deep links, zip-to-row matching by squashed name, and progress).

**Built 2026-08-23: a real importer.** `gridline/IMPORTER.md` has the design.
The picker's Import button reads the file over Figma's REST API and writes a
Gridline file directly -- no plugin, no zip, no per-file clicking. It drives
`common/src/app/common/files/builder.cljc`, which is what `@penpot/library`
(the package the exporter plugin uses) is compiled from, and persists with
`bfc/save-file!`.

Converts pages, frames, groups, rectangles, ellipses, text and lines with
geometry, solid fills, strokes, corner radius and opacity. Does NOT convert
vectors, gradients, images, components, auto-layout or effects -- those become
counted placeholders and the count is reported. Keep that honesty.

Next for the importer, roughly in order of value:
1. **Images** -- fetch `imageRef` fills via `/v1/files/:key/images`, store as
   file media. Probably the biggest visible gap.
2. **Vectors** -- `?geometry=paths` returns fill/stroke path data; map to
   Penpot `:path` shapes. The other big gap.
3. **Gradients** -- linear and radial map fairly directly onto Penpot fills.
4. **Components** -- Figma COMPONENT/INSTANCE onto Penpot components, so
   instances stay linked rather than flattened to boards.
5. **Auto-layout** -- Figma layout modes onto Penpot flex layout.

**Still open.** Auto-matching depends on the exporter naming its zip after the
file; unmatched zips import fine but leave the row unticked. The worklist is
component state, so it does not survive a reload yet — persist it in profile
props next.

**Where this touches the code.** `frontend/src/app/main/ui/onboarding/`, the
dashboard import path, and `backend` file-import RPC. Keep our changes additive
and behind a flag (`enable-gridline-onboarding`) so upstream merges stay clean.

---

## 2. Fork hygiene

- Keep all our code under `gridline/` and clearly-marked flags. Every edit to an
  upstream file is a future merge conflict; prefer new files and extension points.
- `sync-upstream.sh` runs against upstream `main` (stable), not `develop`.

## 3. Later

- SSO for the team (Penpot supports `enable-login-with-oidc`) so onboarding
  doesn't need passwords at all.
- Move assets from the filesystem volume to S3-compatible storage on the home lab
  (`PENPOT_OBJECTS_STORAGE_BACKEND=s3`) once the assets volume gets unwieldy.
- Automated nightly backup + restore drill, instead of the manual `backup.sh`.
