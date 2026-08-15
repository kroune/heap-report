// app/state.js — current-dump selection. The only app-level mutable state
// (CONTRACTS.md). Persistence: #dump=<id> in location.hash; boot() restores
// it via restoreDump().

let current = null;
const listeners = new Set();

export function getDump() {
  return current;
}

export function setDump(id) {
  if (id === current) return;          // notify only on actual change
  current = id;
  writeHash(id);
  for (const fn of [...listeners]) fn(id);
}

export function onDumpChange(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

// Reads #dump=<id> back into state. Called once by boot(); notifies only when
// the hash actually names a (different) dump. Returns the restored id or null.
export function restoreDump() {
  const id = readHash();
  if (id && id !== current) {
    current = id;
    for (const fn of [...listeners]) fn(id);
  }
  return current;
}

function readHash() {
  if (typeof location === 'undefined') return null;
  const m = /^#dump=(.*)$/.exec(location.hash || '');
  return m ? decodeURIComponent(m[1]) : null;
}

function writeHash(id) {
  if (typeof location === 'undefined') return;
  if (id == null) {
    if (typeof history !== 'undefined') history.replaceState(null, '', location.pathname + location.search);
  } else {
    const h = '#dump=' + encodeURIComponent(id);
    if (location.hash !== h) location.hash = h;
  }
}
