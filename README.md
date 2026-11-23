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
├── backend/             Java 24+, Maven wrapper -> target/vyom-erp.jar
│   └── src/main/java/org/ajeet/
│       ├── Main.java
│       ├── web/ApiServer.java              REST routes
│       ├── auth/AuthStore.java             accounts, roles, sessions
│       ├── attendance/AttendanceStore.java punches + device users (read side)
│       ├── sync/SyncRequests.java          "Sync device" queue + agent heartbeat
│       ├── leave/LeaveStore.java           leave requests, approvals, yearly quotas (LTS)
│       └── config/Env.java                 env / .env lookup
└── frontend/            React 18 + Vite (Node 22, see .nvmrc)
```

## Development

Needs **Docker Desktop**, **JDK 24+** and **Node 22**. Maven isn't needed — use
the wrapper in `backend/`.

```bash
cp .env.example .env              # once
docker compose up -d db           # PostgreSQL on localhost:5433

cd backend && ./mvnw -q compile exec:java        # API on :8080 (reads ../.env)
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
| Director | See everyone's attendance; add anyone as director / manager / employee; edit roles, reporting lines, device link and status; reset passwords; run device sync. All directors have equal access. |
| Manager  | See attendance of the people who report to them; add **employees** only (must name at least one reporting manager). |
| Employee | See their own attendance. Every account is an employee. |

One person can hold several roles; the sidebar switcher changes the dashboard
(the last choice is remembered per browser). An employee can report to several
managers, each optionally tagged with a project. The last active director can't
be demoted or disabled. `ADMIN_EMAIL` / `ADMIN_PASSWORD` seed the first director
when no active director exists; on first start, old `admin` accounts become
directors and old pending/rejected sign-ups become `disabled`.

| Method | Path | Who | Body / result |
| ------ | ---- | --- | ------------- |
| POST | `/api/auth/login` | anyone | `{email, password}` → account + `roles` + `token` |
| POST | `/api/auth/password` | signed in | `{current, next}` |
| GET  | `/api/attendance/my` | employee | own punches |
| GET  | `/api/attendance/team[?range=today]` | manager | reportees' punches |
| GET  | `/api/attendance/today`, `/all` | director | everyone's punches |
| GET  | `/api/team` | manager | reportees (one row per project line) |
| GET  | `/api/people` | director | everyone + roles + `reportsTo` |
| GET  | `/api/people/options` | director, manager | pickable managers + unlinked device users |
| POST | `/api/people` | director, manager | `{email, name, password, enrollNumber?, roles?, reportsTo: [{managerId, project?}]}` |
| PUT  | `/api/people/{id}` | director | `{name, enrollNumber, roles?, status?, reportsTo?}` (missing `enrollNumber` unlinks) |
| POST | `/api/people/{id}/password` | director | `{password}`; signs them out |
| POST | `/api/sync` | director | `{since?}` → queues a sync for the agent, waits up to 25 s (`202` if still running) |
| GET  | `/api/sync/status` | director | agent `online`, host, last sync, last error |
| GET  | `/api/leave/my` | signed in | own requests + this year's `balance` per category |
| POST | `/api/leave` | signed in | `{category: personal\|sick, from, to, halfDay?: first\|second, reason}` |
| POST | `/api/leave/{id}/approve`, `/reject` | reporting manager, director | `{note?}`; not your own request; first decision wins |
| POST | `/api/leave/{id}/cancel` | owner, reporting manager, director | owner: pending, or approved and not started |
| GET  | `/api/leave/team` | manager | reportees' requests |
| GET  | `/api/leave/all` | director | everyone's requests |
| GET / PUT | `/api/leave/quotas` | signed in / director | `{personal, sick}` days per calendar year |

**Leave (LTS):** Sundays inside a range don't count; a half-day is 0.5. A
request must fit in the yearly quota counting approved + pending days, and
can't cross into a new year. Quotas default to 12 personal / 6 sick.

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
| `leave_requests`, `leave_quotas` | dashboard | dashboard |

## Local dev with production data

`db/vyom_erp.sql` is a snapshot of the production database (login sessions excluded).
To load it into your local docker Postgres (this replaces the local tables):

```bash
./db/restore.sh
```

To refresh the snapshot on the VPS:

```bash
docker exec vyom-erp-db-1 sh -c 'pg_dump -U $POSTGRES_USER -d $POSTGRES_DB --no-owner --no-privileges --clean --if-exists --exclude-table-data=sessions' > db/vyom_erp.sql
```
