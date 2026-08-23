'use strict';

// Tiny JSON store in userData: remembers window geometry and the selected
// instance. Not worth a dependency.

const fs = require('fs');
const path = require('path');
const { app } = require('electron');

const FILE = () => path.join(app.getPath('userData'), 'gridline-state.json');

function read() {
  try {
    return JSON.parse(fs.readFileSync(FILE(), 'utf8'));
  } catch {
    return {};
  }
}

function write(patch) {
  const next = { ...read(), ...patch };
  try {
    fs.mkdirSync(path.dirname(FILE()), { recursive: true });
    fs.writeFileSync(FILE(), JSON.stringify(next, null, 2));
  } catch (err) {
    // Losing window position is not worth crashing over.
    console.warn('gridline: could not persist state:', err.message);
  }
  return next;
}

module.exports = { read, write };
