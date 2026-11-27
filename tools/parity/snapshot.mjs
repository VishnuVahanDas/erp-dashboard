#!/usr/bin/env node
// API parity snapshot: log in as each configured account, call every read
// endpoint and save {status, body}. Run once against the old backend and once
// against the new one, then compare with diff.mjs. Every account also asks for
// the other accounts' data, so permission answers (403) are recorded too.
//
//   node tools/parity/snapshot.mjs <name>        e.g. old, spring
//   PARITY_BASE_URL=http://localhost:8081 node tools/parity/snapshot.mjs spring
//
// Reads tools/parity/accounts.json (git-ignored, see accounts.example.json).
// Writes tools/parity/out/<name>/ (git-ignored: it holds personal data).
// Only GET requests are sent, apart from one login per account.

import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const name = process.argv[2];
if (!name || !/^[\w-]+$/.test(name)) {
  console.error('usage: node tools/parity/snapshot.mjs <name>   (letters, digits, - or _)');
  process.exit(2);
}

const cfg = JSON.parse(await readFile(join(here, 'accounts.json'), 'utf8'));
// PARITY_BASE_URL overrides the file, e.g. to snapshot a backend run natively on another port
const base = (process.env.PARITY_BASE_URL || cfg.baseUrl || 'http://localhost:8088').replace(/\/$/, '') + '/api';
const outDir = join(here, 'out', name);

async function get(path, token) {
  const res = await fetch(base + path, { headers: token ? { Authorization: `Bearer ${token}` } : {} });
  const text = await res.text();
  let body;
  try { body = JSON.parse(text); } catch { body = { nonJson: text.slice(0, 500) }; }
  return { status: res.status, body };
}

async function login(a) {
  const res = await fetch(base + '/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: a.email, password: a.password }),
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(`login failed for ${a.label}: ${res.status} ${body.error || ''}`);
  return body;
}

/** Stable key order so a diff only shows real changes. */
function sorted(v) {
  if (Array.isArray(v)) return v.map(sorted);
  if (v && typeof v === 'object') return Object.fromEntries(Object.keys(v).sort().map((k) => [k, sorted(v[k])]));
  return v;
}

const file = (path) => path.replace(/^\//, '').replace(/[^\w.-]+/g, '_') + '.json';

// ---------------------------------------------------------------- the run

const sessions = [];
for (const a of cfg.accounts) {
  const me = await login(a);
  const id = me.id ?? me.account?.id;
  sessions.push({ label: a.label, token: me.token, id });
  console.log(`logged in: ${a.label} (#${id}, ${(me.roles || me.account?.roles || []).join(', ')})`);
}
const personIds = [...new Set([...(cfg.personIds || []), ...sessions.map((s) => s.id).filter((x) => x != null)])];

// months: configured, or the newest payroll runs a director can see, plus this month
let months = cfg.months || [];
if (!months.length) {
  for (const s of sessions) {
    const r = await get('/payroll/runs', s.token);
    if (r.status === 200 && Array.isArray(r.body.runs)) { months = r.body.runs.map((x) => x.month).slice(0, 2); break; }
  }
}
const thisMonth = new Date().toISOString().slice(0, 7);
const attMonths = [...new Set([thisMonth, ...months])];

const paths = [
  '/status', '/sync/status',
  '/attendance/my', '/attendance/team', '/attendance/team?range=today', '/attendance/today', '/attendance/all',
  '/notifications?as=employee', '/notifications?as=manager', '/notifications?as=director',
  '/punch-fixes/my', '/punch-fixes/missing', '/punch-fixes/team',
  '/team', '/people', '/people/options',
  '/leave/my', '/leave/team', '/leave/all', '/leave/quotas',
  '/mbo/my', '/mbo/team', '/mbo/all', '/mbo/managers',
  '/payroll/salaries', '/payroll/recommendations', '/payroll/holidays', '/payroll/runs', '/payroll/my',
  '/overtime/my', '/overtime/team',
  '/nope',
];
for (const id of personIds) {
  for (const m of attMonths) paths.push(`/attendance/person/${id}?month=${m}`);
  paths.push(`/payroll/person/${id}`, `/overtime/person/${id}`);
  for (const m of months) paths.push(`/payroll/runs/${m}/${id}/days`);
}
for (const m of months) paths.push(`/payroll/runs/${m}`);

await rm(outDir, { recursive: true, force: true });
let n = 0;
for (const s of [...sessions, { label: 'anonymous', token: null }]) {
  const dir = join(outDir, s.label);
  await mkdir(dir, { recursive: true });
  for (const p of paths) {
    const r = await get(p, s.token);
    await writeFile(join(dir, file(p)), JSON.stringify(sorted({ path: p, ...r }), null, 2) + '\n');
    n++;
  }
}
console.log(`saved ${n} responses for ${sessions.length} account(s) + anonymous -> ${outDir}`);
