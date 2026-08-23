# Penpotter roadmap

What we're building on top of upstream Penpot. Ordered by when we need it.

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
report the same thing. The public profile email from `/user` is deliberately
still treated as unverified — GitHub makes no ownership guarantee about it.

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
with their Figma work already inside Penpotter — not with an empty dashboard and
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
