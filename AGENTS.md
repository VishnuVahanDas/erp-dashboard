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
    web/Guard.java               role checks for controllers (Guard.requireRole / requireAnyRole), 403 messages
    web/ApiExceptionHandler      errors -> 401 (AuthException) / 403 (Forbidden) / 400 (IllegalArgumentException,
                                 bad JSON) / 500, always {"error": ...}
    web/NotFoundController       any other /api path or method -> 404 {"error": "not found"}
    web/Forbidden.java           unchecked 403 for services and guards
    web/JsonBody.java            reads a request body (any content type, empty = {}); JsonBody.number for numbers
    auth/AuthController          /auth/* login, logout, me, own password
    auth/PeopleController        /team, /people/* (directors manage everyone, managers add employees)
    auth/AuthService             accounts, roles, reporting lines, sessions, PBKDF2 passwords, first-director
                                 seed; people changes run one at a time (last-director + device-link rules)
    auth/AuthRepository          its SQL and tables (accounts, account_roles, reporting_lines, sessions)
    attendance/Attendance{Controller,Service,Repository}  /attendance/* punches + device users (read side;
                                 creates the shared users/punches tables, before every other repository)
    attendance/PunchFix{Controller,Service,Repository}  /punch-fixes/* missed-punch requests (own table; never
                                 writes punches); approvedPunches feeds the attendance views
    notify/NotificationController  /notifications (the bell), reads the other services
    sync/SyncController          /status, POST /sync, /sync/status (directors)
    sync/SyncRepository          "Sync device" queue + agent heartbeat (shared tables, DDL = sdk's)
    leave/Leave{Controller,Service,Repository}  /leave/* requests, approvals, company + per-person yearly
                                 quotas (LTS); apply / approve lock the employee (quota + overlap)
    mbo/Mbo{Controller,Service,Repository}  /mbo/* weekly MBOs ("Objectives" in the UI; up to 6 per manager
                                 per week, grouped by month, expire when the week ends); the limit locks the pair
    payroll/Payroll{Controller,Service,Repository}  /payroll/* salaries, holidays, monthly runs; run changes
                                 lock the month, salary changes the person
    payroll/PayrollRules         the pay rules (compute, missedMinutes, SHIFT_MIN...): no database, unit-tested
    overtime/Overtime{Controller,Service,Repository}  /overtime/* claims (own table); tier rule in
                                 OvertimeService.otDays, counted from a 9:00 h span (OT_START_MIN; a full day for
                                 cuts is PayrollRules.SHIFT_MIN = 8:30 h); payroll pays approved days
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
                 its day rules copy PayrollRules (SHIFT_MIN, HALF_DAY_REQUIRED_MIN, GRACE_MIN), so change both together
  src/ui.jsx     shared components (Modal, Stat, Icon...) and date/time helpers
  src/App.css    all styles
docker-compose.yml  db (postgres:17) + backend + web (nginx), project name pinned to vyom-erp
```

## How the code is built (follow these patterns)

- **Spring Boot, one package per area:** `<area>/<Area>Controller` (HTTP),
  `<Area>Service` (rules and messages) and `<Area>Repository` (SQL), see `mbo/`.
  A new feature usually means a new package with those three and a new `.jsx`
  module in the frontend.
  - **Controller:** `@RequestMapping("/api/...")`; `@CurrentAccount
    AuthService.Account me` first (so the session is checked before the body);
    `Guard.requireRole` for roles; a body as `@RequestBody(required = false)
    byte[]` read with `JsonBody`, fields read with defaults
    (`path("x").asString("")`, `asLong(0)`, `JsonBody.number`); path ids as
    `{id:\d+}` strings parsed with `Long.parseLong`; responses are records or
    maps. Errors are exceptions, never hand-built responses.
  - **Service:** the rules and their messages; throws `Forbidden` (403),
    `IllegalArgumentException` (400) or `AuthService.AuthException` (401);
    writes are `@Transactional(rollbackFor = Exception.class)`. Requests run in
    parallel on a connection pool, so a check-then-write (a quota, a limit, the
    last director) must hold a lock in its transaction: `pg_advisory_xact_lock`
    on what it protects (see `lockAccount`, `lockPair`, `lockMonth`) or `SELECT
    ... FOR UPDATE`; a one-per-X rule is best a unique index (catch
    `DuplicateKeyException`). Decisions update `WHERE status = 'pending'`, so the
    first one wins.
  - **Repository:** SQL on `JdbcClient` (no JPA). It creates its own tables in
    its constructor (next point); `@DependsOn` when its tables reference another
    area's (`authRepository` for accounts).
  - JSON stored in a column (payroll_lines.days) is read with a mapper that
    allows missing numbers: Jackson 3 refuses them by default and older rows
    lack newer fields.
  - Backend changes that must not change the API (refactors, upgrades): take a
    `tools/parity` snapshot before and after and diff them.
- **Each module owns its tables.** Every repository creates its own tables with
  `CREATE TABLE IF NOT EXISTS` (plus any idempotent `ALTER ... IF NOT EXISTS`
  migrations) at startup. There is no migration tool yet. Schema changes must be
  additive and safe to re-run on a database that already has data, and the app
  must still start on an empty database (tables are created in dependency
  order: attendance, then accounts, then the rest).
- **Keep dependencies minimal.** The backend uses Spring Boot starters (webmvc,
  jdbc, actuator) and the PostgreSQL driver (JSON is Spring's Jackson 3); the
  frontend only React. Add another library only when the user asks for one.
- **Roles:** director, manager, employee (one person can hold several). A
  manager only sees people who report to them through `reporting_lines`. Every
  new endpoint must enforce this scoping on the server, not just in the UI.
  Directors report to nobody: `AuthService` refuses reporting lines for a
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
- Passwords are PBKDF2-hashed in `AuthService`. Never log or return password
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
