# AGENTS.md — erp-dashboard (web-dashboard)

Instructions for AI coding agents working in this repository.

This repo is the **Vyom ERP attendance portal**: a Java REST API, a React UI
and PostgreSQL, run with Docker Compose on a VPS. It is usually checked out as
the `web-dashboard/` submodule of the `vyom-erp` parent repo; on the VPS it is
cloned on its own (as `erp-dashboard/`). Read [README.md](README.md) first. It
documents the API, roles, leave and MBO rules, and the database contract.

## Layout

```
backend/   Java 25, Spring Boot 4.1 (webmvc, jdbc, actuator), Maven wrapper
  src/main/resources/application.properties   settings: env vars, then ../.env or .env (same names as .env.example)
  src/main/java/org/ajeet/
    ErpApplication.java          Spring Boot entry point
    web/WebConfig.java           CORS + the @CurrentAccount parameter
    web/CurrentAccount           controller parameter: Bearer token -> account, else 403 "invalid or expired session"
    web/Guard.java               role checks for controllers (Guard.requireRole), same 403 messages as before
    web/ApiExceptionHandler      controller errors -> 401 / 403 / 400 / 500 with {"error": ...}
    web/Forbidden.java           unchecked 403 for ported services and guards
    web/JsonBody.java            reads a request body like the old Http.readJson (any type, empty = {})
    web/LegacyWiring.java        MIGRATION: opens the not-yet-ported stores, seeds the director, lists
                                 the route modules in their old match order (what Main used to do)
    web/LegacyBridgeController   MIGRATION: /api/** that no Spring controller claims -> ApiServer
    web/ServletExchange.java     MIGRATION: a servlet request seen as a JDK HttpExchange for the old routes
    web/ApiServer.java           MIGRATION: CORS, dispatch to the route modules, errors -> status codes
    web/Http.java                MIGRATION: shared helpers for the old routes: JSON, session + role guards
    web/routes/Routes.java       the route-module interface: handle(ex, method, path) -> handled?
    web/routes/<Module>Routes    one per area still on the old style: Auth, Attendance, People,
                                 Leave, Payroll, Overtime (Jackson 2)
    auth/AuthStore.java          accounts, roles, reporting lines, sessions, PBKDF2 passwords
    attendance/AttendanceStore   punches + device users (read side)
    attendance/PunchFix{Controller,Service,Repository}  PORTED: /punch-fixes/* missed-punch requests
                                 (own table; never writes punches); approvedPunches feeds the attendance views
    notify/NotificationController  PORTED: /notifications (the bell), reads the other stores
    sync/SyncController          PORTED: /status, POST /sync, /sync/status (directors)
    sync/SyncRepository          "Sync device" queue + agent heartbeat (JdbcClient; shared tables, DDL = sdk's)
    leave/LeaveStore.java        leave requests, approvals, company + per-person yearly quotas (LTS)
    mbo/MboController            PORTED: /mbo/* (weekly MBOs, shown as "Objectives" in the UI; up to 6 per
                                 manager per week, grouped by month, expire when the week ends)
    mbo/MboService               rules + @Transactional writes; the weekly limit is held by a lock on the pair
    mbo/MboRepository            SQL (JdbcClient) and its tables' DDL / migration at startup
    payroll/PayrollStore.java    salaries, holidays, monthly runs; pay rules in PayrollStore.compute
    overtime/OvertimeStore.java  overtime claims (own table); tier rule in OvertimeStore.otDays, counted
                                 from a 9:00 h span (OT_START_MIN; a full day for cuts is PayrollStore.SHIFT_MIN
                                 = 8:30 h); payroll pays approved days
  src/test/java/org/ajeet/       unit tests for the pay, leave and overtime rules
tools/parity/  API snapshot + diff, to prove a backend refactor changes no response
frontend/  React 19 + Vite 6, plain JSX, no router or state library
  src/App.jsx    shell, login, role dashboards, attendance views
  src/leave.jsx  leave module UI
  src/mbo.jsx    MBO module UI (labelled "Objectives") + Performance panel
  src/payroll.jsx  payroll UI: director run / salaries / holidays, manager view, payslips
  src/team.jsx   People → Teams view and the person details dialog
  src/punchfix.jsx  missed-punch requests + incomplete-day list (employee) and approval queue (dashboard)
  src/overtime.jsx  overtime claims (employee, in Payslips) and Payroll → Overtime (approve / decline / grant)
  src/notify.jsx   notifications bell (GET /api/notifications)
  src/calendar.jsx  My calendar on the employee's My dashboard: holidays, leave and attendance per day;
                 its day rules copy PayrollStore (SHIFT_MIN, HALF_DAY_REQUIRED_MIN, GRACE_MIN), so change both together
  src/ui.jsx     shared components (Modal, Stat, Icon...) and date/time helpers
  src/App.css    all styles
docker-compose.yml  db (postgres:17) + backend + web (nginx), project name pinned to vyom-erp
```

## How the code is built (follow these patterns)

- **Spring Boot migration in progress** (branch `feat/spring-boot`, baseline tag
  `pre-spring-boot`). Spring Boot runs the server; areas not yet ported still go
  through `LegacyBridgeController` → `ApiServer` → `web/routes/*Routes` exactly
  as before. Each area moves to a Spring controller, which takes priority over
  the bridge automatically. Keep `/api` paths, JSON, status codes and error
  messages identical: the `tools/parity` diff against the `old` snapshot must stay
  empty. Don't add features in the old style meanwhile. The two notes below on
  routes and dependencies describe the old style until the move is finished.
- **Porting an area** (see `mbo/`, the first full one): `<Area>Controller`
  (`@RequestMapping("/api/...")`, `@CurrentAccount` first, `Guard` for roles,
  body as `@RequestBody(required = false) byte[]` read with `JsonBody`, the same
  response records), `<Area>Service` (rules, messages, `@Transactional(rollbackFor
  = Exception.class)` on writes, throws `Forbidden` / `IllegalArgumentException`)
  and `<Area>Repository` (the same SQL on `JdbcClient`, its DDL in the constructor;
  `@DependsOn("legacyStores")` while it references tables an old store creates).
  Where the old store relied on `synchronized` for a check-then-write, hold a
  lock in the transaction (`pg_advisory_xact_lock`, `SELECT ... FOR UPDATE`).
  Then remove the store and routes from `LegacyWiring`, delete them, and check:
  unit tests, parity diff, and the area's writes by hand for each role.
- **Each module owns its tables.** Every `*Store` creates its own tables with
  `CREATE TABLE IF NOT EXISTS` (plus any idempotent `ALTER ... IF NOT EXISTS`
  migrations) at startup. There is no migration tool. Schema changes must be
  additive and safe to re-run on a database that already has data.
- **Routes live in `web/routes/<Module>Routes.java`; `ApiServer` only
  dispatches.** Each module implements `Routes`, gets only the stores it needs
  through its constructor and uses `Http` for JSON and guards. Role checks
  happen in the route module before a store is called; SQL and business rules
  stay in the `*Store` classes. `LegacyWiring` lists the modules in the order they are
  asked, and inside a module the first matching branch wins, so put a specific
  path (`/leave/quotas/{id}`) where it can't be shadowed. A new feature usually
  means a new `*Store` package, a new `*Routes` class, wiring both in `LegacyWiring`,
  and a new `.jsx` module in the frontend.
- **Keep dependencies minimal.** The backend uses Spring Boot starters (webmvc,
  jdbc, actuator), the PostgreSQL driver and, until the move is done, Jackson 2
  for the old routes; the frontend only React. Don't add another framework or
  library unless the user asks for one.
- **Roles:** director, manager, employee (one person can hold several). A
  manager only sees people who report to them through `reporting_lines`. Every
  new endpoint must enforce this scoping on the server, not just in the UI.
  Directors report to nobody: `AuthStore` refuses reporting lines for a
  director and clears them when someone becomes one.
- **Audit trails are append-only.** MBO entries are never edited or deleted,
  and leave decisions record who decided and when. Keep it that way.
- **Time:** punch times are device-local wall-clock times (`LocalDateTime`, no
  zone). Created/decided timestamps are `TIMESTAMPTZ` with `now()` in UTC.
  Don't mix the two.
- **Render `Modal` and `Toasts` outside `<section className="panel">`** (return a
  fragment, as `leave.jsx` does). `.panel` has `backdrop-filter`, which makes it
  the containing block for `position: fixed`, so a dialog inside it is squeezed
  into the panel instead of covering the screen.
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
cd backend && ./mvnw -q spring-boot:run           # API on :8080 (reads ../.env)
cd frontend && npm install && npm run dev         # UI on :5173, proxies /api
docker compose up -d --build                      # full stack as in production -> :8088
```

Before committing:
- `cd backend && ./mvnw -q test` must pass: unit tests for the pay, leave and
  overtime rules (`backend/src/test/`). Change a rule → change its test in the
  same commit, and say why in the message.
- `cd backend && ./mvnw -q -DskipTests package` must succeed.
- `cd frontend && npm run build` must succeed.
- No local JDK 25 / Node? `docker compose build backend web` runs the same
  Maven and Vite builds inside the images.
- For behaviour changes, run the app and check the affected flow for each role
  it touches (director / manager / employee).
- Backend refactors that must not change the API (such as the Spring Boot move):
  take an API snapshot before and after with `tools/parity/` and diff them (see
  README → Testing). `tools/parity/accounts.json` and `tools/parity/out/` hold
  real logins and personal data, are git-ignored, and must never be committed.

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
