#!/usr/bin/env node
// Compare two API snapshots from snapshot.mjs.
//
//   node tools/parity/diff.mjs old spring
//
// Prints each response whose status or body differs, with the JSON paths that
// changed. Values that change on their own between runs (agent "seconds ago")
// are ignored. Exit code 1 when anything differs.

import { readdir, readFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const [a, b] = process.argv.slice(2);
if (!a || !b) { console.error('usage: node tools/parity/diff.mjs <snapshotA> <snapshotB>'); process.exit(2); }

const VOLATILE = /SecondsAgo$/;
const MAX_PER_FILE = 12;

async function files(root) {
  const out = new Map();
  for (const who of await readdir(root)) {
    for (const f of await readdir(join(root, who))) out.set(`${who}/${f}`, join(root, who, f));
  }
  return out;
}

function diff(x, y, path, acc) {
  if (acc.length >= MAX_PER_FILE) return;
  if (Array.isArray(x) && Array.isArray(y)) {
    if (x.length !== y.length) acc.push(`${path || '$'}: array length ${x.length} -> ${y.length}`);
    for (let i = 0; i < Math.min(x.length, y.length); i++) diff(x[i], y[i], `${path}[${i}]`, acc);
    return;
  }
  if (x && y && typeof x === 'object' && typeof y === 'object') {
    for (const k of new Set([...Object.keys(x), ...Object.keys(y)])) {
      if (VOLATILE.test(k)) continue;
      if (!(k in y)) acc.push(`${path}.${k}: missing in ${b}`);
      else if (!(k in x)) acc.push(`${path}.${k}: only in ${b}`);
      else diff(x[k], y[k], `${path}.${k}`, acc);
    }
    return;
  }
  if (x !== y) acc.push(`${path || '$'}: ${JSON.stringify(x)} -> ${JSON.stringify(y)}`.slice(0, 300));
}

const A = await files(join(here, 'out', a));
const B = await files(join(here, 'out', b));
let bad = 0;
for (const key of [...new Set([...A.keys(), ...B.keys()])].sort()) {
  if (!A.has(key) || !B.has(key)) { console.log(`✗ ${key}: only in ${A.has(key) ? a : b}`); bad++; continue; }
  const x = JSON.parse(await readFile(A.get(key), 'utf8'));
  const y = JSON.parse(await readFile(B.get(key), 'utf8'));
  const acc = [];
  diff(x, y, '', acc);
  if (acc.length) {
    bad++;
    console.log(`✗ ${key}`);
    for (const line of acc) console.log(`    ${line}`);
  }
}
console.log(bad ? `\n${bad} of ${A.size} response(s) differ` : `all ${A.size} responses match`);
process.exit(bad ? 1 : 0);
