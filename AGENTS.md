# AGENTS.md — erp-dashboard (web-dashboard)

Instructions for AI coding agents working in this repository.

This repo is the **Vyom ERP attendance portal**: a Java REST API, a React UI
and PostgreSQL, run with Docker Compose on a VPS. It is usually checked out as
the `web-dashboard/` submodule of the `vyom-erp` parent repo; on the VPS it is
cloned on its own (as `erp-dashboard/`). Read [README.md](README.md) first. It
documents the API, roles, leave and MBO rules, and the database contract.

## Layout

```
backend/   Java 24, Maven wrapper, no framework
  src/main/java/org/ajeet/
    Main.java                    wires the stores and starts ApiServer
    web/ApiServer.java           all REST routes (JDK com.sun.net.httpserver + Jackson)
    auth/AuthStore.java          accounts, roles, reporting lines, sessions, PBKDF2 passwords
    attendance/AttendanceStore   punches + device users (read side)
    sync/SyncRequests.java       "Sync device" queue + agent heartbeat
    leave/LeaveStore.java        leave requests, approvals, yearly quotas (LTS)
    mbo/MboStore.java            MBO threads and entries
    payroll/PayrollStore.java    salaries, holidays, monthly runs; pay rules in PayrollStore.compute
    config/Env.java              env var / .env lookup
frontend/  React 19 + Vite 6, plain JSX, no router or state library
  src/App.jsx    shell, login, role dashboards, attendance views
  src/leave.jsx  leave module UI
  src/mbo.jsx    MBO module UI + Performance panel
  src/ui.jsx     shared components (Modal, Stat, Icon...) and date/time helpers
  src/App.css    all styles
docker-compose.yml  db (postgres:17) + backend + web (nginx), project name pinned to vyom-erp
```

## How the code is built (follow these patterns)

- **Each module owns its tables.** Every `*Store` creates its own tables with
  `CREATE TABLE IF NOT EXISTS` (plus any idempotent `ALTER ... IF NOT EXISTS`
  migrations) at startup. There is no migration tool. Schema changes must be
  additive and safe to re-run on a database that already has data.
- **Routes live in `ApiServer.java`**, and role checks happen there before a
  store is called. A new feature usually means a new `*Store` package, routes
  in `ApiServer`, wiring in `Main`, and a new `.jsx` module in the frontend.
- **Keep dependencies minimal.** The backend uses only Jackson and the
  PostgreSQL driver; the frontend only React. Don't add a framework or library
  unless the user asks for one.
- **Roles:** director, manager, employee (one person can hold several). A
  manager only sees people who report to them through `reporting_lines`. Every
  new endpoint must enforce this scoping on the server, not just in the UI.
- **Audit trails are append-only.** MBO entries are never edited or deleted,
  and leave decisions record who decided and when. Keep it that way.
- **Time:** punch times are device-local wall-clock times (`LocalDateTime`, no
  zone). Created/decided timestamps are `TIMESTAMPTZ` with `now()` in UTC.
  Don't mix the two.
- Derived views (like the MBO Performance panel) can be computed in the
  frontend from data already loaded when that's enough. Add an API only when
  the data isn't already available.

## Shared database contract with the sync agent

The `sdk` repo (Windows sync agent) writes `punches` and `users`, and shares
`sync_requests` and `sync_agent` with this repo. Both repos create these four
tables. **If you change any of them, make the matching change in the `sdk`
repo too** and keep the "Database contract" tables in both READMEs in step.
`punches` is unique on `(enroll_number, punch_time, verify_label)`, so changing
how `verify_label` is written creates duplicate punches unless existing rows
are migrated.

## Build, run, check

```bash
cp .env.example .env                              # once; never commit .env
docker compose up -d db                           # PostgreSQL on localhost:5433
cd backend && ./mvnw -q compile exec:java         # API on :8080
cd frontend && npm install && npm run dev         # UI on :5173, proxies /api
docker compose up -d --build                      # full stack as in production -> :8088
```

There is no automated test suite. Before committing:
- `cd backend && ./mvnw -q -DskipTests package` must succeed.
- `cd frontend && npm run build` must succeed.
- For behaviour changes, run the app and check the affected flow for each role
  it touches (director / manager / employee).

## Security rules (hard requirements)

- **Never commit `db/` or any database dump.** It is git-ignored because a
  production dump (personal data, password hashes) was once committed and had
  to be purged from history.
- **Never commit `.env`.** Only `.env.example` is tracked. Add new settings
  there with a safe placeholder and document them in the README.
- Don't commit `backend/target/`, `frontend/dist/` or `node_modules/`.
- Passwords are PBKDF2-hashed in `AuthStore`. Never log or return password
  hashes or session tokens from the API.

## Git workflow

- Commit messages follow Conventional Commits with a scope, as in the history:
  `feat(mbo): ...`, `fix(dashboard): ...`, `fix(docker): ...`, `chore(db): ...`.
  The body says *why*.
- Work on `feat/<topic>` branches for non-trivial changes and merge them into
  `main`. Delete the branch after merging, once it has no unmerged commits.
- No force-pushes or history rewrites without explicit approval from the user.
- **After anything lands on `main`, update the pointer in the `vyom-erp`
  parent repo:** `git add web-dashboard` there, commit as
  `chore: bump erp-dashboard (<summary>)` and push. Push this repo first. See
  the parent's AGENTS.md.

## Keep the docs current

Update the docs in the same commit as the change they describe. Don't leave it
for later:

- **README.md:** new or changed endpoints (the API tables), roles and
  permissions, leave/MBO rules, settings in `.env.example`, setup or deploy
  steps, and the database contract table.
- **AGENTS.md (this file):** new modules or files in the layout, new
  conventions, new commands, or rules learned from a mistake.
- If the change affects the shared tables or how the two repos fit together,
  also update the `sdk` README and the `vyom-erp` README/AGENTS.md.
