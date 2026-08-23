# Gridline Desktop

An Electron shell around our Penpot instance. Dock icon, its own window, no
browser chrome, and separate logins per instance.

## Why Electron and not Tauri

Tauri is the lighter choice — it uses the system WebView instead of bundling a
browser, so the app would be ~5 MB rather than ~100 MB. We still picked Electron:

- On macOS Tauri renders in **WKWebView**, i.e. Safari's engine. Penpot's own
  recommended settings say to use the latest stable Chrome, and its WASM render
  engine and canvas work are tuned and tested against Chromium.
- Electron bundles Chromium, so the desktop app runs the *exact* engine Penpot
  targets. Bugs we hit will be bugs the upstream project also sees.
- 100 MB is irrelevant for an internal tool installed on a handful of Macs.

Worth revisiting if Penpot's WebKit support becomes a first-class target.

## Run it

```bash
cd gridline/desktop
npm install
npm start              # home lab instance
npm run start:dev      # local dev stack on :9101
GRIDLINE_URL=http://localhost:3449 npm start   # anything else, e.g. the devenv
```

## Build a DMG

```bash
npm run dist           # arm64 -> dist/Gridline-0.1.0-arm64.dmg
npm run dist:universal # if anyone is still on Intel
```

The build is **unsigned** — we have no Apple Developer certificate. The first
launch needs a right-click → Open, or `xattr -dr com.apple.quarantine
/Applications/Gridline.app`. Signing + notarisation is a paid Apple account;
worth it only once this goes to people outside the team.

## What the shell does

- **Instance menu** — switch between the home lab and the local dev stack. Each
  gets its own persistent session partition, so you can be logged into both at
  once without the cookie jars fighting. The choice is remembered.
- **Off-origin links open in the real browser** — docs, OAuth callbacks, shared
  prototypes on other hosts. Only our instance loads inside the shell.
- **Window geometry persists** across launches.
- **Single instance** — a second launch focuses the existing window rather than
  opening a rival one against the same session.
- **A useful error when the instance is unreachable**, with a Retry button. The
  usual cause is Tailscale not being connected on this Mac.

The renderer loads a remote origin, so it runs with `nodeIntegration: false`,
`contextIsolation: true` and `sandbox: true`. There is no preload bridge and the
page gets no access to the filesystem beyond ordinary browser downloads.

## Changing the instance URLs

Edit [`src/instances.js`](src/instances.js). Nothing else hardcodes a host.
