/* Teams — the People tab grouped by manager, and one person's details (leave, attendance, payroll, MBO). */

import { useEffect, useMemo, useState } from 'react';
import { Avatar, Empty, Icon, Modal, call, fmtDuration, groupByDay, minutesBetween, todayStr } from './ui.jsx';
import { hrs, money, monthLabel } from './payroll.jsx';
import { PersonQuotaDialog } from './leave.jsx';

const CAT = { personal: 'Personal', sick: 'Sick' };
const LEAVE_PILL = { approved: 'live', pending: 'warn', rejected: 'bad', cancelled: 'idle' };
const SHORT_MIN = 9 * 60 - 10; // a present day with a shorter span than the 9 h shift (10 min grace)

const thisMonth = () => todayStr().slice(0, 7);
const shiftMonth = (ym, n) => {
  const d = new Date(`${ym}-01T00:00:00Z`);
  d.setUTCMonth(d.getUTCMonth() + n);
  return d.toISOString().slice(0, 7);
};
const fmtD = (s) => new Date(`${s}T00:00:00Z`).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', timeZone: 'UTC' });
const num = (n) => Math.round(n * 100) / 100;

/** Approved leave covering today. */
const onLeaveToday = (leaves, id) => {
  const t = todayStr();
  return leaves.some((r) => r.accountId === id && r.status === 'approved' && r.from <= t && r.to >= t);
};

/* ---------------------------------------------------------------- teams */

/**
 * A team is a manager plus everyone with a reporting line to them. Someone
 * with two managers is in both teams; a manager can be in someone else's.
 */
function buildTeams(people) {
  const byId = new Map(people.map((p) => [p.id, p]));
  const teams = new Map();
  for (const p of people) {
    for (const l of p.reportsTo || []) {
      const t = teams.get(l.managerId) || {
        managerId: l.managerId, manager: byId.get(l.managerId), managerName: l.managerName, members: new Map(),
      };
      const m = t.members.get(p.id) || { person: p, projects: [] };
      if (l.project) m.projects.push(l.project);
      t.members.set(p.id, m);
      teams.set(l.managerId, t);
    }
  }
  return [...teams.values()]
    .map((t) => ({ ...t, members: [...t.members.values()].sort((a, b) => (a.person.name || a.person.email).localeCompare(b.person.name || b.person.email)) }))
    .sort((a, b) => b.members.length - a.members.length || (a.managerName || '').localeCompare(b.managerName || ''));
}

function MembersTable({ members, leaves, onOpen, showManagers }) {
  return (
    <div className="table-wrap">
      <table className="leave-table">
        <thead><tr><th>Person</th><th>Roles</th>{showManagers && <th>Also reports to</th>}<th>Device</th><th>Today</th></tr></thead>
        <tbody>
          {members.map(({ person: p, projects, others }) => (
            <tr key={p.id} className="mbo-row" onClick={() => onOpen(p)}>
              <td><div className="who"><Avatar label={p.name || p.email} /><span>{p.name || p.email}<small>{p.email}{projects?.length ? ` · ${projects.join(', ')}` : ''}</small></span></div></td>
              <td className="dim">{(p.roles || []).filter((r) => r !== 'employee').join(', ') || 'employee'}</td>
              {showManagers && <td className="dim">{others?.length ? others.join(', ') : '—'}</td>}
              <td className="mono">{p.enrollNumber != null ? `#${p.enrollNumber}` : <span className="pay-flag">not linked</span>}</td>
              <td>{p.status !== 'active' ? <span className="pill idle">{p.status}</span>
                : onLeaveToday(leaves, p.id) ? <span className="pill warn">on leave</span> : <span className="dim">—</span>}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Director's People tab: teams with headcount; open one to see its members. */
export function TeamsView({ people, leaves, onOpenPerson }) {
  const [open, setOpen] = useState(null); // managerId | 'none'
  const active = people.filter((p) => p.status === 'active');
  const teams = useMemo(() => buildTeams(active), [people]); // eslint-disable-line react-hooks/exhaustive-deps
  // directors report to nobody, so they aren't "missing" a team
  const noTeam = active.filter((p) => !(p.reportsTo || []).length && !(p.roles || []).includes('director'));
  const inactive = people.length - active.length;

  if (open != null) {
    const t = teams.find((x) => x.managerId === open);
    const members = open === 'none'
      ? noTeam.map((p) => ({ person: p, projects: [] }))
      : (t?.members || []).map((m) => ({
        ...m, others: (m.person.reportsTo || []).filter((l) => l.managerId !== open).map((l) => l.managerName),
      }));
    return (
      <section className="panel">
        <div className="panel-head">
          <button className="btn ghost small" onClick={() => setOpen(null)}>‹ All teams</button>
          <span className="muted">{members.length} {open === 'none' ? 'people' : 'member(s)'}</span>
        </div>
        {open === 'none' ? (
          <div className="team-head"><div><span className="eyebrow">No manager</span><h3>People not in a team</h3>
            <small className="dim">People nobody has been set to manage (directors don’t need a manager).</small></div></div>
        ) : (
          <div className="team-head mbo-row" onClick={() => t?.manager && onOpenPerson(t.manager)} title="Open the manager's details">
            <Avatar label={t?.managerName} />
            <div><span className="eyebrow">Team of</span><h3>{t?.managerName}</h3>
              <small className="dim">{t?.manager?.email}{t?.manager?.reportsTo?.length ? ` · reports to ${t.manager.reportsTo.map((l) => l.managerName).join(', ')}` : ''}</small></div>
          </div>
        )}
        {!members.length ? <Empty title="Nobody here" hint="" />
          : <MembersTable members={members} leaves={leaves} onOpen={onOpenPerson} showManagers={open !== 'none'} />}
      </section>
    );
  }

  return (<>
    <div className="team-summary">
      <span><b>{teams.length}</b> team(s)</span>
      <span><b>{active.length}</b> people in total, managers included</span>
      {noTeam.length > 0 && <span><b>{noTeam.length}</b> not in a team</span>}
      {inactive > 0 && <span className="dim">{inactive} disabled / pending account(s) not counted</span>}
    </div>
    {!teams.length && !noTeam.length ? <section className="panel"><Empty title="No people yet" hint="Add people and set who they report to." /></section> : (
      <div className="team-cards">
        {teams.map((t) => {
          const leaveToday = t.members.filter((m) => onLeaveToday(leaves, m.person.id)).length;
          const managers = t.members.filter((m) => (m.person.roles || []).includes('manager')).length;
          return (
            <button key={t.managerId} className="team-card" onClick={() => setOpen(t.managerId)}>
              <div className="who"><Avatar label={t.managerName} /><span><b>{t.managerName}</b><small>manager</small></span></div>
              <div className="team-count"><b>{t.members.length}</b><span>member{t.members.length === 1 ? '' : 's'}</span></div>
              <div className="team-faces">{t.members.slice(0, 6).map((m) => <Avatar key={m.person.id} label={m.person.name || m.person.email} />)}
                {t.members.length > 6 && <em>+{t.members.length - 6}</em>}</div>
              <small className="dim">{managers ? `${managers} manager(s) inside · ` : ''}{leaveToday ? `${leaveToday} on leave today` : 'nobody on leave today'}</small>
            </button>
          );
        })}
        {noTeam.length > 0 && (
          <button className="team-card muted-card" onClick={() => setOpen('none')}>
            <div className="who"><Avatar label="?" /><span><b>Not in a team</b><small>no manager set</small></span></div>
            <div className="team-count"><b>{noTeam.length}</b><span>{noTeam.length === 1 ? 'person' : 'people'}</span></div>
            <small className="dim">{noTeam.map((p) => p.name || p.email).slice(0, 3).join(', ')}{noTeam.length > 3 ? '…' : ''}</small>
          </button>
        )}
      </div>
    )}
  </>);
}

/* --------------------------------------------------------- person details */

function Attendance({ me, person }) {
  const [month, setMonth] = useState(thisMonth());
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  useEffect(() => {
    setData(null); setError(null);
    call('GET', `/api/attendance/person/${person.id}?month=${month}`, null, me.token)
      .then(setData).catch((e) => setError(e.message));
  }, [me.token, person.id, month]);

  const stats = useMemo(() => {
    if (!data?.punches) return null;
    const days = groupByDay(data.punches); // each day oldest -> newest
    const list = Object.entries(days).map(([d, ps]) => ({
      d, minutes: ps.length > 1 ? minutesBetween(ps[0].time, ps[ps.length - 1].time) : 0,
    }));
    const total = list.reduce((s, x) => s + x.minutes, 0);
    return {
      present: list.length, total, avg: list.length ? Math.round(total / list.length) : 0,
      short: list.filter((x) => x.minutes < SHORT_MIN).length,
      single: Object.values(days).filter((ps) => ps.length === 1).length,
    };
  }, [data]);

  return (
    <section className="person-sec">
      <div className="person-sec-head">
        <h4><Icon name="clock" size={15} />Attendance</h4>
        <div className="month-step">
          <button className="icon-btn neutral" title="Previous month" onClick={() => setMonth(shiftMonth(month, -1))}>‹</button>
          <span>{monthLabel(month)}</span>
          <button className="icon-btn neutral" title="Next month" disabled={month >= thisMonth()} onClick={() => setMonth(shiftMonth(month, 1))}>›</button>
        </div>
      </div>
      {error ? <div className="inline err">{error}</div> : !data ? <p className="muted">Loading…</p> : !data.linked ? (
        <p className="pay-flag">Not linked to the attendance device — no punches.</p>
      ) : (
        <div className="person-tiles">
          <div><span>Days present</span><b>{stats.present}</b><small>with at least one punch</small></div>
          <div><span>Hours logged</span><b>{fmtDuration(stats.total)}</b><small>first → last punch</small></div>
          <div><span>Average a day</span><b>{fmtDuration(stats.avg)}</b><small>on days present</small></div>
          <div className={stats.short ? 'warn' : ''}><span>Short days</span><b>{stats.short}</b>
            <small>{stats.single ? `${stats.single} with a single punch` : 'under the 9 h shift'}</small></div>
        </div>
      )}
    </section>
  );
}

function Payroll({ me, person }) {
  const [state, setState] = useState(null);
  useEffect(() => {
    (async () => {
      try {
        const [sal, runs] = await Promise.all([
          call('GET', '/api/payroll/salaries', null, me.token),
          call('GET', '/api/payroll/runs', null, me.token),
        ]);
        const salary = (sal.salaries || []).find((r) => r.accountId === person.id && r.status === 'approved');
        const locked = (runs.runs || []).filter((r) => r.status !== 'draft');
        let line = null;
        for (const r of locked.slice(0, 3)) { // newest locked month the person is in
          const l = ((await call('GET', `/api/payroll/runs/${r.month}`, null, me.token)).lines || []).find((x) => x.accountId === person.id);
          if (l) { line = l; break; }
        }
        setState({ salary, line });
      } catch (e) { setState({ error: e.message }); }
    })();
  }, [me.token, person.id]);

  return (
    <section className="person-sec">
      <div className="person-sec-head"><h4><Icon name="money" size={15} />Payroll</h4></div>
      {!state ? <p className="muted">Loading…</p> : state.error ? <p className="dim">{state.error}</p> : (
        <div className="person-tiles">
          <div><span>Salary / month</span><b>{state.salary ? money(state.salary.gross) : '—'}</b>
            <small>{state.salary ? `since ${state.salary.effectiveFrom}` : 'not on payroll'}</small></div>
          <div><span>Last payslip</span><b>{state.line ? money(state.line.net) : '—'}</b>
            <small>{state.line ? `${monthLabel(state.line.month)} · ${state.line.status}` : 'none finalized yet'}</small></div>
          <div className={state.line?.cut ? 'warn' : ''}><span>Cut that month</span><b>{state.line ? money(state.line.cut) : '—'}</b>
            <small>{state.line ? `${hrs(Math.max(0, state.line.missedHours + state.line.correctionHours))} missed` : ''}</small></div>
        </div>
      )}
    </section>
  );
}

/**
 * One person's details. leaves / balanceOf / mbos are what the People tab's
 * parent already loaded (scoped to the viewer); attendance and payroll are
 * fetched here through scoped endpoints.
 */
export function PersonDialog({ me, person, leaves, balanceOf, mbos, onLeaveChanged, onClose }) {
  const year = new Date().getFullYear();
  const balance = balanceOf ? balanceOf(person.id, year) : [];
  const [quotaOpen, setQuotaOpen] = useState(false);
  const [saved, setSaved] = useState(null);
  // a director for anyone, a manager for their reportees (the only people they can open); never yourself
  const canSetQuota = person.id !== me.id && balance.length > 0;
  const theirLeave = leaves.filter((r) => r.accountId === person.id);
  const threads = mbos.filter((t) => t.employeeId === person.id);
  const agreed = threads.filter((t) => t.status === 'acknowledged').length;
  const denied = threads.filter((t) => t.status === 'denied').length;
  const expired = threads.filter((t) => t.status === 'expired').length; // a week that ended unagreed
  const notAgreed = denied + expired;
  const open = threads.filter((t) => t.status === 'open');
  const managers = (person.reportsTo || []).map((l) => l.managerName);

  return (<>
    {/* while the quota dialog is open, Escape / backdrop close only that one */}
    <Modal title={person.name || person.email} eyebrow="Person" onClose={quotaOpen ? () => {} : onClose}>
      <div className="person-top">
        <Avatar label={person.name || person.email} />
        <div>
          <b>{person.email}</b>
          <small>
            {(person.roles || ['employee']).join(', ')}
            {' · '}{person.enrollNumber != null ? `device #${person.enrollNumber}` : 'not linked to the device'}
            {managers.length ? ` · reports to ${managers.join(', ')}` : person.project ? ` · ${person.project}` : ''}
          </small>
        </div>
        {onLeaveToday(leaves, person.id) && <span className="pill warn">on leave today</span>}
      </div>

      <section className="person-sec">
        <div className="person-sec-head"><h4><Icon name="calendar" size={15} />Leave {year}</h4>
          {canSetQuota && <button className="btn ghost small" onClick={() => setQuotaOpen(true)}><Icon name="edit" size={15} />Edit quota</button>}
        </div>
        <div className="person-tiles">
          {balance.map((b) => (
            <div key={b.category} className={b.remaining <= 0 ? 'warn' : ''}>
              <span>{CAT[b.category]} leave</span>
              <b>{num(b.remaining)}<em> / {b.quota} left</em></b>
              <small>{num(b.used)} taken · {num(b.pending)} pending · {b.custom ? 'own quota' : 'company default'}</small>
            </div>
          ))}
        </div>
        {saved && <div className="inline ok">{saved}</div>}
        {theirLeave.length > 0 ? (
          <ul className="perf-list person-list">
            {theirLeave.slice(0, 5).map((r) => (
              <li key={r.id}>
                <span><b>{CAT[r.category]} · {r.from === r.to ? fmtD(r.from) : `${fmtD(r.from)} – ${fmtD(r.to)}`}</b>
                  <small>{num(r.days)} day(s){r.halfDay ? ` · ${r.halfDay}${r.hours ? ` ${r.hours} h` : ''}` : ''} · {r.reason}</small></span>
                <span className={`pill ${LEAVE_PILL[r.status]}`}>{r.status}</span>
              </li>
            ))}
          </ul>
        ) : <p className="dim">No leave requests.</p>}
      </section>

      <Attendance me={me} person={person} />
      <Payroll me={me} person={person} />

      <section className="person-sec">
        <div className="person-sec-head"><h4><Icon name="chat" size={15} />MBOs</h4></div>
        <div className="person-tiles">
          <div><span>Objectives agreed</span><b>{agreed}</b><small>of {threads.length} MBO(s)</small></div>
          <div><span>Agreement rate</span><b>{agreed + notAgreed ? `${Math.round((agreed / (agreed + notAgreed)) * 100)}%` : '—'}</b><small>{denied} denied · {expired} expired</small></div>
          <div className={open.some((t) => t.waitingOn === 'employee') ? 'warn' : ''}><span>Open</span><b>{open.length}</b>
            <small>{open.filter((t) => t.waitingOn === 'employee').length} waiting on them</small></div>
        </div>
      </section>
    </Modal>
    {quotaOpen && <PersonQuotaDialog me={me} person={person} balance={balance} onClose={() => setQuotaOpen(false)}
                                     onDone={(m) => { setQuotaOpen(false); setSaved(m); onLeaveChanged?.(); }} />}
  </>);
}
