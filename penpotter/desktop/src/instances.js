'use strict';

// Which Penpot instance the shell points at. `prod` is the home lab over
// Tailscale; `dev` is whatever the local compose stack is serving.
//
// Override at runtime with PENPOTTER_URL, or pick a named one with
// PENPOTTER_INSTANCE=dev. The choice is remembered between launches.

const INSTANCES = {
  prod: { label: 'Home lab', url: 'https://lab.tail101716.ts.net:8443' },
  dev: { label: 'Local dev', url: 'http://localhost:9101' },
};

const DEFAULT_INSTANCE = 'prod';

// Each instance gets its own persistent session, so being logged into the home
// lab and the local stack at once doesn't clobber either cookie jar.
function partitionFor(url) {
  const { host } = new URL(url);
  return `persist:penpotter-${host.replace(/[^a-z0-9]/gi, '-')}`;
}

module.exports = { INSTANCES, DEFAULT_INSTANCE, partitionFor };
