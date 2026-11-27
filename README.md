# Vyom ERP — Web Dashboard

Attendance portal: Java REST API (`backend/`) + React UI (`frontend/`), backed
by PostgreSQL. Punches are written into the same database by the Windows sync
agent (the **sdk** repo); this project never talks to the device.

```
frontend (React, nginx) ──/api──> backend (Java :8080) ──> PostgreSQL <── sync agent (Windows)
```

```
web-dashboard/
├── docker-compose.yml   db + backend + web (dev and VPS)
├── .env.example         copy to .env
├── backend/             Java 25, Spring Boot 4.1, Maven wrapper -> target/vyom-erp.jar
│   ├── src/main/resources/application.properties   settings (env vars, then .env)
│   └── src/main/java/org/ajeet/
│       ├── ErpApplication.java             Spring Boot entry point
│       ├── web/LegacyWiring.java           opens the stores, seeds the first director (migration step)
│       ├── web/LegacyBridgeController.java /api requests not yet on a Spring controller -> ApiServer
│       ├── web/ApiServer.java              old dispatch: CORS, route modules, errors -> status codes
│       ├── web/Http.java                   shared JSON / session / role helpers for the old routes
│       ├── web/routes/*Routes.java         REST routes, one class per module (Auth, People, Attendance,
│       │                                   PunchFix, Leave, Mbo, Payroll, Overtime, Notification)
│       ├── auth/AuthStore.java             accounts, roles, sessions
│       ├── attendance/AttendanceStore.java punches + device users (read side)
│       ├── attendance/PunchFixStore.java   missed-punch requests and approved fixes
│       ├── sync/SyncController.java        Spring controller: /status, device sync (ported)
│       ├── sync/SyncRepository.java        "Sync device" queue + agent heartbeat
│       ├── leave/LeaveStore.java           leave requests, approvals, company + personal yearly quotas (LTS)
│       ├── mbo/MboStore.java               weekly MBOs ("Objectives" in the UI, 6 per manager per week): propose / acknowledge / deny / suggest, expiry
│       ├── payroll/PayrollStore.java       salaries, holidays, monthly payroll runs, payslips
│       └── overtime/OvertimeStore.java     overtime claims and approvals (quarter-day tiers)
├── tools/parity/        API snapshot + diff (see Testing)
└── frontend/            React 19 + Vite 6 (Node 22, see .nvmrc)
```

## Development

Needs **Docker Desktop**, **JDK 25** and **Node 22**. Maven isn't needed — use
the wrapper in `backend/`.

The backend is being moved to **Spring Boot** step by step (branch
`feat/spring-boot`). Spring Boot runs the server; areas not ported yet still
answer through the original route classes, so the API is unchanged. Settings
use the same names as before: process environment first, then `.env` (read as
a properties file, so a value containing `\` needs it doubled).

Ported so far: **Sync** (`/status`, `/sync`, `/sync/status`). Deliberate changes
on ported endpoints: a request body that isn't valid JSON answers **400**
`request body is not valid JSON` (it was a 500 with the parser's message).

```bash
cp .env.example .env              # once
docker compose up -d db           # PostgreSQL on localhost:5433

cd backend && ./mvnw -q spring-boot:run          # API on :8080 (reads ../.env); health: /actuator/health
cd frontend && npm install && npm run dev        # UI on http://localhost:5173 (proxies /api)
```

Or run the whole stack in Docker (same as production):

```bash
docker compose up -d --build      # http://localhost:8088
docker compose logs -f backend
docker compose down               # stop (data kept in the pgdata volume)
docker compose down -v            # stop AND wipe the database
```

To test **Sync device** locally, run the sdk agent on a Windows PC pointed at
your database; without an agent the dashboard shows it as offline.

### Testing

```bash
cd backend && ./mvnw -q test      # unit tests: pay (PayrollStore.compute), leave day counting, overtime bands
```

**API parity check** (for backend changes that must not change any response):

```bash
cp tools/parity/accounts.example.json tools/parity/accounts.json   # real director / manager / employee logins
node tools/parity/snapshot.mjs old      # against the running stack (baseUrl, default :8088)
# ... switch the backend to the new code ...
node tools/parity/snapshot.mjs new
node tools/parity/diff.mjs old new      # lists every response whose status or JSON changed
```

The snapshot only sends GET requests (plus one login per account). Each account
also asks for the others' data, so permission answers are compared too.
`accounts.json` and `tools/parity/out/` are git-ignored: they hold passwords
and personal data.

## Production (VPS)

Copy (or `git clone`) only this folder to the VPS, then:

```bash
cp .env.example .env   # set strong POSTGRES_PASSWORD and ADMIN_PASSWORD
docker compose up -d --build
```

- **Web access:** `web` listens on `127.0.0.1:8088`. Put a TLS reverse proxy
  (Caddy / nginx + Let's Encrypt) in front of it, or set `WEB_BIND=0.0.0.0`.
- **Sync agent access:** the Windows agent connects straight to PostgreSQL. Set
  `DB_BIND=0.0.0.0` (and `DB_PORT`), then allow that port **only from the
  office's public IP** in the VPS firewall (e.g. `ufw allow from <office-ip> to
  any port 5433`). Never leave it open to everyone.
- Updates: `git pull && docker compose up -d --build`. Data lives in the
  `pgdata` volume.

## Roles & people

There is no self sign-up. Accounts are created inside the portal:

| Role     | Can do |
| -------- | ------ |
| Director | See everyone's attendance; add anyone as director / manager / employee; edit roles, reporting lines, device link and status; reset passwords; run device sync. All directors have equal access. Directors report to nobody (no reporting manager). |
| Manager  | See attendance of the people who report to them; add **employees** only (must name at least one reporting manager); set a reportee's personal leave quota. |
| Employee | See their own attendance. Every account is an employee. |

One person can hold several roles; the sidebar switcher changes the dashboard
(the last choice is remembered per browser). An employee can report to several
managers, each optionally tagged with a project. Directors sit at the top and
have no reporting manager: the API refuses reporting lines for a director, and
saving someone as a director removes any lines they had. The last active
director can't be demoted or disabled. `ADMIN_EMAIL` / `ADMIN_PASSWORD` seed the first director
when no active director exists; on first start, old `admin` accounts become
directors and old pending/rejected sign-ups become `disabled`.

| Method | Path | Who | Body / result |
| ------ | ---- | --- | ------------- |
| POST | `/api/auth/login` | anyone | `{email, password}` → account + `roles` + `token` |
| POST | `/api/auth/password` | signed in | `{current, next}` |
| GET  | `/api/attendance/my` | employee | own punches, plus approved fixes as entries with `addedBy` (the approver) |
| GET  | `/api/attendance/team[?range=today]` | manager | reportees' punches (+ approved fixes, as above) |
| GET  | `/api/notifications?as=employee\|manager\|director` | signed in (a role you hold) | items waiting on you in that role: `{kind, id, title, detail, tab, at}` |
| GET  | `/api/punch-fixes/my` | signed in | own missed-punch requests |
| GET  | `/api/punch-fixes/missing` | signed in | own past days (last 31, month not locked) with an odd number of punches and no fix asked for: `{day, punches: [HH:mm]}` |
| POST | `/api/punch-fixes` | signed in | `{day, in: HH:mm, out: HH:mm, reason}` — a past day, month not finalized, one open fix per day |
| POST | `/api/punch-fixes/{id}/cancel` | owner | while pending |
| GET  | `/api/punch-fixes/team` | manager (reportees), director (all) | requests to decide |
| POST | `/api/punch-fixes/{id}/approve`, `/reject` | director, the person's manager (never own) | `{note}`: optional to approve (a comment), required to reject; shown to the employee |
| GET  | `/api/attendance/person/{accountId}?month=YYYY-MM` | director (anyone), manager (reportees) | one person's punches that month (+ approved fixes) + `linked`; for the People details |
| GET  | `/api/attendance/today`, `/all` | director | everyone's punches (`/all` + approved fixes) |
| GET  | `/api/team` | manager | reportees (one row per project line) |
| GET  | `/api/people` | director | everyone + roles + `reportsTo` |
| GET  | `/api/people/options` | director, manager | pickable managers + unlinked device users |
| POST | `/api/people` | director, manager | `{email, name, password, enrollNumber?, roles?, reportsTo: [{managerId, project?}]}` |
| PUT  | `/api/people/{id}` | director | `{name, enrollNumber, roles?, status?, reportsTo?}` (missing `enrollNumber` unlinks) |
| POST | `/api/people/{id}/password` | director | `{password}`; signs them out |
| POST | `/api/sync` | director | `{since?}` → queues a sync for the agent, waits up to 25 s (`202` if still running) |
| GET  | `/api/sync/status` | director | agent `online`, host, last sync, last error |
| GET  | `/api/leave/my` | signed in | own requests + this year's `balance` per category |
| POST | `/api/leave` | signed in | `{category: personal\|sick, from, to, halfDay?: first\|second\|late\|early, hours?, reason}`; `hours` only for late/early |
| POST | `/api/leave/{id}/approve`, `/reject` | reporting manager, director | `{note?}`; not your own request; first decision wins |
| POST | `/api/leave/{id}/cancel` | owner, reporting manager, director | owner: pending, or approved and not started |
| GET  | `/api/leave/team` | manager | reportees' `requests`, plus `usage` (approved + pending days per person, year and category), `quotas` and `personQuotas` (`{accountId, category, days, setBy, setAt}`) |
| GET  | `/api/leave/all` | director | everyone's `requests`, `usage`, `quotas` and `personQuotas` |
| GET / PUT | `/api/leave/quotas` | signed in / director | company default `{personal, sick}` days per calendar year |
| PUT  | `/api/leave/quotas/{accountId}` | director (anyone), the person's manager (reportees); never your own | `{personal?, sick?}`: that person's own days per year; `null` = back to the company default, a missing key = unchanged. Returns their `balance` |

**Leave (LTS):** Sundays and company holidays (the list a director keeps in
Payroll → Holidays, also shown on the leave calendar) inside a range don't count; a half-day is 0.5. A
**late arrival** or **early departure** is 0.5–3 hours of one date in
half-hour steps, counted as hours ÷ 8 of a day; 3.5 hours or more is a half day
(first half for late, second half for early), so the API refuses more than 3. Every partial request (half-day,
late, early) is a single date. Late and first half take the morning, early and
second half the evening, so late + early on one day is fine but late + first
half clashes. A request must fit in the yearly quota counting approved + pending
days, and can't cross into a new year. The company quotas default to 12
personal / 6 sick.

**Personal quotas:** quotas differ per person. A director (anyone) or the
person's manager (their reportees) sets that person's own days per category
with **Edit quota** in their details (People / My team, click the person);
nobody sets their own. An empty box keeps the company default. The personal
quota is used for the balance, for applying and for approving, every year
until it's changed; leave already approved stays approved. Each personal
quota records who set it and when (`leave_person_quotas`).

The director's **Dashboard** has a leave panel: pending requests with Approve /
Reject, who is on leave today, upcoming approved leave, and shortcuts to the
quotas and the full Leave requests tab. Employees see their leave balance on
their home page. Approvers see the employee's Personal and Sick balance (for
the request's year, pending counted as booked) on each pending request and in
the Approve / Reject dialog.

The employee's home page (**My dashboard**) also has **My calendar**: one month
at a time, each day marked as a holiday, Sunday, approved or pending leave, or
by attendance: worked (with the first-to-last span), short of the day, a missing
punch or absent. It uses the payroll day rules: Mon-Sat, not a holiday, an
8:30 h span lowered by approved leave, 10 minutes of grace. Pending leave
doesn't excuse a day. Days before the person's first punch aren't marked absent.
Clicking a day shows its punches, leave and holiday. It is built in the browser
from `/api/attendance/my`, `/api/leave/my` and `/api/payroll/holidays`, with no
endpoint of its own.

| Method | Path | Who | Notes |
|---|---|---|---|
| GET  | `/api/mbo/my` | signed in | own MBO threads with every entry; each has `weekStart` (that Monday, `yyyy-MM-dd`; `null` for an earlier MBO) and `status` open / acknowledged / denied / expired |
| GET  | `/api/mbo/team` | manager | reportees' threads |
| GET  | `/api/mbo/all` | director | everyone's threads except your own |
| GET  | `/api/mbo/managers` | signed in | who you can send an MBO to: your reporting managers + directors |
| POST | `/api/mbo` | signed in | manager: `{employeeId, title, body}`; employee: `{managerId, title, body}`. Always for the current week; 400 once that employee already has 6 this week with that manager |
| POST | `/api/mbo/{id}/acknowledge`, `/deny`, `/suggest` | the side whose turn it is (employee, or the named manager / a director) | `{body}`; required for deny/suggest |

**MBOs (shown as Objectives):** the UI calls an MBO an **objective** and its
back-and-forth a **conversation** (sidebar tab *Objectives*); the API paths
(`/api/mbo/...`) and tables (`mbo_threads`, `mbo_entries`) keep the MBO name.
A thread is between one employee and one named manager. A manager
starting it is that manager; an employee picks one of their reporting managers
or a director. The manager side is answered by that manager or any director
(other reporting managers can read it). Whoever starts it proposes; the other side then
acknowledges (closed, agreed), denies (closed, not agreed) or suggests changes
(turn flips back). Entries are never edited or deleted, so the trail can back
promotion reviews.

**Weekly objectives:** objectives are weekly and grouped by month: a year is 12
months of 4–5 weeks. A week runs Monday–Sunday and belongs to the month its
Monday is in (Mon 30 Nov – Sun 6 Dec is November · Week 5). Each employee can
have up to **6 per manager per week**, whatever their status (someone with two
reporting managers can have 12); only the current week's can be started, and
the 7th is refused until next Monday. An objective still open when its week
ends is closed as **expired**: nobody can answer it any more, its entries stay
as they were, and it counts as not agreed. "Today" is the database's date.
MBOs from before weekly objectives have no week, stay as they were (they don't
expire) and are listed under **Earlier objectives**.

Managers and directors also get a **Performance** panel on the Objectives tab: per
employee, over a chosen period, the objectives agreed, agreement rate (agreed ÷
agreed + denied + expired), MBOs they
raised themselves, average reply time and anything waiting on them, plus "Most
agreed", "Most initiative" and "Quickest replies" badges. It is computed in the
browser from the threads the tab already loads; there is no extra API.

| Method | Path | Who | Notes |
|---|---|---|---|
| GET / POST | `/api/payroll/salaries` | director, manager | salary rows with `status` (managers: their reportees) / `{accountId, basic, hra?, otherAllowance?, effectiveFrom}` adds an approved row — directors for anyone, managers for their reportees, never yourself |
| POST | `/api/payroll/salaries/{id}/approve`, `/reject` | director, the person's manager | `{note}` (required to reject); pending proposals only, first decision wins |
| GET  | `/api/payroll/recommendations` | director, manager | recommendations (managers: their reportees) with `status` pending/accepted/declined |
| POST | `/api/payroll/recommendations` | manager (reportees only) | `{accountId, startDate?, note?}` — put someone on payroll without an amount |
| POST | `/api/payroll/recommendations/{id}/decline` | director, the person's manager | `{note}` required |
| GET  | `/api/payroll/holidays` | signed in | `{day, name}` list |
| POST, DELETE | `/api/payroll/holidays`, `/api/payroll/holidays/{yyyy-MM-dd}` | director | add/rename `{day, name}` / remove |
| GET  | `/api/payroll/runs` | director, manager | months with status, `people` and `totalNet`; managers only see finalized/paid months, and their `people` / `totalNet` cover only their reportees (never company totals) |
| GET  | `/api/payroll/runs/{yyyy-MM}` | director, manager | lines; managers get their reportees, finalized/paid months only |
| GET  | `/api/payroll/runs/{yyyy-MM}/{accountId}/days` | director, the employee, their manager | day-by-day breakdown (finalized months for non-directors) |
| POST | `/api/payroll/runs/{yyyy-MM}/generate` | director | (re)builds a draft month; corrections are kept |
| POST | `/api/payroll/runs/{yyyy-MM}/{accountId}/correction` | director | `{hours, note}` ± missed hours, draft only; note required |
| POST | `/api/payroll/runs/{yyyy-MM}/finalize`, `/paid` | director | draft → finalized (locked) → paid `{paidOn?}` |
| GET  | `/api/payroll/my` | signed in | own finalized/paid payslips |
| GET  | `/api/payroll/person/{accountId}` | director (every month), the person's manager, self (finalized/paid months) | one person's lines across months, newest first |

Lines carry `overtimeDays` and `overtimePay`; each day of the breakdown carries
`otMin` and `otDays`; a run carries `overtimeChanged` (a draft with overtime
approved after it was generated, so it needs regenerating).

| Method | Path | Who | Notes |
|---|---|---|---|
| GET  | `/api/overtime/my` | signed in | `{claims, available}`: own claims, and own past days (last 62, month not locked) with overtime that nobody claimed |
| POST | `/api/overtime` | signed in | `{day, reason?}` — a past day whose punches show a 10:00 h span or more; the server works out the overtime |
| POST | `/api/overtime/{id}/cancel` | owner | while pending |
| GET  | `/api/overtime/team` | director (all), manager (reportees) | `{claims, available}` to decide or approve directly |
| GET  | `/api/overtime/person/{accountId}` | director, the person's manager, self | one person's claims |
| POST | `/api/overtime/{id}/approve`, `/reject` | director, the person's manager (never own) | `{note}`: optional to approve, required to reject; approving re-reads the punches |
| POST | `/api/overtime/grant` | director, the person's manager (never own) | `{accountId, day, note?}` — approve a day without a claim |

**Payroll:** fixed monthly salary (basic + HRA + other allowance), cut only for
hours missed, plus approved overtime. Day rate = gross ÷ days in that month
(28–31); hour rate = day rate ÷ 8. Every Mon–Sat that isn't a holiday needs an
8:30 h first-to-last punch span (lunch included). Approved leave lowers it:
full day → not counted, half-day → 4 h, late/early of X h → 8:30 − X h. Missed time per day is capped at
8 h (an absent day costs one day's pay); up to 10 minutes is forgiven, more is
rounded up to the next half hour. Sundays and holidays are paid. While a month
is running, only days up to yesterday count. Someone whose first salary row
starts mid-month is paid from that date. The director can add a ± hours
correction with a note (forgotten punch, unlinked account) before finalizing;
a finalized month can't change. Net pay = gross − cut + overtime pay.

**Overtime:** counted past the 9 h office day (first to last punch, 1 h lunch
included, widened by an approved missed-punch fix), in whole hours rounded
down, one quarter day per 2-hour band. 8:30 h is a full day with no cut; between
8:30 and 10:00 nothing is cut or paid.

| Span (in → out) | Whole hours past 9 h | Paid |
|---|---|---|
| under 10:00 | 0 | nothing |
| 10:00 – 11:59 | 1–2 | ¼ day |
| 12:00 – 13:59 | 3–4 | ½ day |
| 14:00 – 15:59 | 5–6 | ¾ day |
| 16:00 or more | 7+ | 1 day (the most for one day) |

The same rule applies on any day, Sundays
and holidays included. Only approved overtime is paid, at the day rate
(overtime days × gross ÷ days in the month), in the month it was worked. An
employee claims a past day from **Payslips → Overtime**, where days whose
punches show overtime are listed; their reporting manager or a director
approves or declines it (with a reason) in **Payroll → Overtime**. They can
also approve a day found in the punches straight away, without a claim. Nobody
decides their own, and a manager only sees their reportees. One open claim per
person per day; once a month's payroll is finalized its overtime can't be
claimed or decided. Approvals after a draft was generated show a "regenerate"
note on the run. The run lines, day breakdown and payslip show the overtime
days and amount, and clicking a person in Payroll opens their pay and overtime
history (managers: reportees, finalized months only).

**Who sets pay:** a director for anyone, or a manager for their own reportees
(never for themselves). A salary saved by either applies at once and closes
open requests for that person (a pending recommendation is accepted, a waiting
proposal is marked replaced). A manager can also **recommend** a reportee who
isn't on payroll without naming an amount; a director or another of that
person's managers accepts it by setting the salary, or declines it with a
reason. Managers open Payroll on **My team**: every reportee with their payroll
status, **Add reportee to payroll** / **Set salary** / **Change**, and the
requests waiting for them. Directors still see and decide everything. Each line stores its day-by-day breakdown as
generated, so a payslip doesn't change if punches are re-synced later. An approved missed-punch
fix widens that day's span (earliest in, latest out of device punches and the
fix) and is noted in the breakdown; pending or rejected fixes are ignored.
Employees see finalized months in a **Payslips** tab (print / save as PDF from
the browser); directors can export a month as CSV for the bank or accountant.

**Notifications:** the bell in the header lists what's waiting on you in the
role you're viewing as — as a director or manager: pending leave, punch fixes,
payroll recommendations / proposals and MBOs on your turn, for the people you
can decide for; as an employee: MBOs on your turn and your own leave / punch
fixes decided in the last week. In every role it also lists your own days with
a missing punch ("You didn't punch properly"); clicking one opens your
employee Punch history, where the fix is requested. It is worked out on each request (no stored
read state) and refreshes every 30 s; clicking an item opens its tab.

**Missed punches:** a past day with an odd number of device punches (checked in
but never out, or out without an in) counts as incomplete. For the last 31 days
(skipping months whose payroll is locked, and days that already have a fix
request) it shows in the bell and at the top of the employee's Punch history with
**Request fix**, prefilled from the device punch (a lone punch before 1 pm is
taken as the check-in, a later one as the check-out). The employee asks for a
fix ("in 09:00, out 18:00 on 12 Sep") and picks what happened from a list
("Forgot to punch out in the evening" / "…in in the morning" is chosen from the
device punch; also both, device didn't record, site visit, other) with optional
details; the reason is stored as that text. Their reporting manager or a
director decides it on the Dashboard: **Approve** in one click, **Decline** with
a reason, or **Comment** to write a note and then approve or decline with it.
The note is shown to the employee in their requests, in the bell and on that
day's row in their Punch history (which also says when a fix is waiting or was
declined). A manager who forgot asks the same
way and their own reporting manager decides; a director's request goes to
another director. Fixes are kept in `punch_fixes` — the device `punches` are
never changed — and payroll uses approved ones when a month is generated. In
the Punch history, Team punches and a person's attendance an approved fix shows as
entries marked **Added by <approver> on request** (a fix time within a minute
of a device punch isn't repeated).

**People → Teams:** directors open People on teams — one per manager, built
from `reporting_lines` (someone with two managers is in both; managers can sit
in another team) — with the total headcount and who isn't in a team. Clicking a
team lists its members; clicking anyone (here, in Everyone, or in a manager's
team list) opens their details: leave taken / pending / left this year and
recent requests, attendance for a month (days present, hours, short days),
current salary and last payslip, and MBO results.

Accounts are created only inside the portal (no sign-up). The backend needs
`PG_URL` and won't start without it.

## Database contract with the sync agent

Both projects create these tables with `CREATE TABLE IF NOT EXISTS`, so either
can start first. If you change one, change the other repo too.

| Table | Written by | Read by |
| ----- | ---------- | ------- |
| `punches` (`enroll_number`, `punch_time`, `verify_label`; unique on all three) | agent | dashboard |
| `users` (`enroll_number`, `name`) — device-enrolled people | agent | dashboard |
| `sync_requests` — "Sync device" queue | dashboard (request), agent (claim/finish) | both |
| `sync_agent` — single heartbeat row | agent | dashboard |
| `accounts`, `account_roles`, `reporting_lines`, `sessions` | dashboard | dashboard |
| `leave_requests`, `leave_quotas`, `leave_person_quotas` | dashboard | dashboard |
| `mbo_threads`, `mbo_entries` | dashboard | dashboard |
| `punch_fixes` — approved missed-punch fixes (never written to `punches`) | dashboard | dashboard |
| `payroll_salaries`, `payroll_recommendations`, `payroll_holidays`, `payroll_runs`, `payroll_lines` (reads `punches`, `leave_requests`, `punch_fixes`, `overtime_claims`) | dashboard | dashboard |
| `overtime_claims` — overtime claims and approvals (reads `punches`, `punch_fixes`) | dashboard | dashboard |

## Local dev with production data

The `db/` folder is **git-ignored and must never be committed**: a production
snapshot holds personal data and password hashes. It was once committed by
mistake and had to be purged from the repo history. Keep dumps on your machine
only, and pass them around by a private channel, never through git.

To take a snapshot on the VPS (login sessions excluded), then copy it to
`db/vyom_erp.sql` on your machine:

```bash
docker exec vyom-erp-db-1 sh -c 'pg_dump -U $POSTGRES_USER -d $POSTGRES_DB --no-owner --no-privileges --clean --if-exists --exclude-table-data=sessions' > vyom_erp.sql
```

To load it into your local docker Postgres (this replaces the local tables),
start the database and pipe the dump in:

```bash
docker compose up -d db
docker compose exec -T db psql -q -v ON_ERROR_STOP=1 -U vyom -d vyom_erp < db/vyom_erp.sql
```

Then log in with your production credentials.
