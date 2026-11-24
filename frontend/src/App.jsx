import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Avatar, DroneMark, Empty, Icon, Modal, Stat, Toasts, call, dayOf, fmtDuration, greeting, groupByDay, hhmm,
  minutesBetween, roster, ThemeToggle, todayStr,
} from './ui.jsx';
import { DashboardLeave, EmployeeLeave, LeaveBalance, OpsLeave, makeBalanceOf } from './leave.jsx';
import { EmployeeMbo, OpsMbo, myTurn } from './mbo.jsx';
import { OpsPayroll } from './payroll.jsx';

const TOKEN_KEY = 'attendance_token';
const roleKey = (id) => `vyom_role_${id}`;

/** Highest first — matches the backend's ordering. */
const ROLE_META = {
  director: { label: 'Director', icon: 'star', nav: 'Command' },
  manager: { label: 'Manager', icon: 'team', nav: 'My team' },
  employee: { label: 'Employee', icon: 'user', nav: 'My space' },
};
const ROLE_ORDER = ['director', 'manager', 'employee'];

const store = {
  get(k) { try { return localStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch { /* private mode */ } },
  del(k) { try { localStorage.removeItem(k); } catch { /* private mode */ } },
};


/* ---------------------------------------------------------------- shared */

function Clock() {
  const [now, setNow] = useState(new Date());
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(t);
  }, []);
  return (
    <div className="clock">
      <span className="clock-time">{now.toLocaleTimeString('en-GB')}</span>
      <span className="clock-date">{now.toLocaleDateString('en-GB', { weekday: 'short', day: '2-digit', month: 'short', year: 'numeric' })}</span>
    </div>
  );
}



function RoleSwitch({ roles, role, onSwitch }) {
  if (roles.length < 2) return null;
  return (
    <div className="role-switch" role="tablist" aria-label="Switch dashboard role">
      {roles.map((r) => (
        <button key={r} role="tab" aria-selected={r === role} className={r === role ? 'active' : ''}
                title={`Switch to ${ROLE_META[r].label} dashboard`} onClick={() => onSwitch(r)}>
          <Icon name={ROLE_META[r].icon} size={15} /><span>{ROLE_META[r].label}</span>
        </button>
      ))}
    </div>
  );
}

function Shell({ me, ctx, nav, tab, setTab, title, subtitle, actions, children }) {
  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          <DroneMark size={38} />
          <div>
            <b>VYOM</b>
            <span>Drones ERP</span>
          </div>
        </div>
        <RoleSwitch roles={me.roles} role={ctx.role} onSwitch={ctx.switchRole} />
        <nav className="side-nav">
          <span className="nav-label">{ROLE_META[ctx.role].nav}</span>
          {nav.map((n) => (
            <button key={n.key} className={`nav-item ${tab === n.key ? 'active' : ''}`} onClick={() => setTab(n.key)}>
              <Icon name={n.icon} />
              <span>{n.label}</span>
              {n.count ? <em className="nav-count">{n.count}</em> : null}
            </button>
          ))}
        </nav>
        <div className="side-foot">
          <div className="me-chip">
            <Avatar label={me.name || me.email} />
            <div>
              <b>{me.name || me.email.split('@')[0]}</b>
              <span>{ROLE_META[ctx.role].label}{me.enrollNumber ? ` · #${me.enrollNumber}` : ''}</span>
            </div>
          </div>
          <ThemeToggle />
          <button className="icon-btn neutral" title="Change password" onClick={ctx.openPassword}><Icon name="key" /></button>
          <button className="icon-btn" title="Sign out" onClick={ctx.logout}><Icon name="logout" /></button>
        </div>
      </aside>

      <main className="main">
        <header className="main-head">
          <div>
            <span className="eyebrow">{subtitle}</span>
            <h1>{title}</h1>
          </div>
          <div className="head-right">
            <Clock />
            {actions}
          </div>
        </header>
        {children}
      </main>
    </div>
  );
}


const ago = (s) => s == null ? 'never' : s < 60 ? `${s}s ago` : s < 3600 ? `${Math.floor(s / 60)}m ago` : `${Math.floor(s / 3600)}h ago`;

function AgentChip({ agent }) {
  if (!agent) return null;
  const name = agent.mode === 'local' ? 'Local device' : agent.host ? `Agent ${agent.host}` : 'Sync agent';
  const state = !agent.host && agent.mode === 'agent' ? 'never connected'
    : !agent.online ? `offline · seen ${ago(agent.lastSeenSecondsAgo)}`
    : agent.lastError ? 'last sync failed'
    : `synced ${ago(agent.lastSyncSecondsAgo)}`;
  const cls = !agent.online ? 'off' : agent.lastError ? 'warn' : 'on';
  return (
    <div className={`agent-chip ${cls}`} title={agent.lastError || agent.device || ''}>
      <i />
      <div><b>{name}</b><span>{state}</span></div>
    </div>
  );
}

function ModeTag({ label }) {
  const k = (label || '').toLowerCase();
  const cls = k.includes('face') ? 'face' : k.includes('card') ? 'card' : k.includes('finger') ? 'finger' : 'other';
  return <span className={`mode-tag ${cls}`}>{label || '—'}</span>;
}

/* ------------------------------------------------------------------ auth */

function AuthScreen({ onLogin }) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError(null);
    try {
      onLogin(await call('POST', '/api/auth/login', { email, password }));
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  };

  return (
    <div className="auth">
      <section className="auth-hero">
        <div className="sky-grid" />
        <svg className="flight" viewBox="0 0 600 400" preserveAspectRatio="none" aria-hidden="true">
          <path id="fp" d="M-20 340 C 120 300, 180 120, 320 160 S 520 60, 640 40" />
          <circle r="5" className="flight-dot">
            <animateMotion dur="9s" repeatCount="indefinite"><mpath href="#fp" /></animateMotion>
          </circle>
        </svg>
        <div className="radar"><div className="sweep" /><i style={{ top: '30%', left: '62%' }} /><i style={{ top: '64%', left: '28%' }} /><i style={{ top: '45%', left: '78%' }} /></div>
        <div className="hero-copy">
          <div className="hero-brand"><DroneMark size={54} /><span>VYOM DRONES</span></div>
          <h2>Every crew member,<br /><em>on the radar.</em></h2>
          <p>Attendance, approvals and crew operations for Vyom Drones Pvt Ltd — synced straight from the hangar terminal.</p>
          <div className="hero-telemetry">
            <div><span>ALT</span><b>120 m</b></div>
            <div><span>HDG</span><b>072°</b></div>
            <div><span>LINK</span><b className="ok">● LIVE</b></div>
          </div>
        </div>
      </section>

      <section className="auth-panel">
        <ThemeToggle className="theme-corner" />
        <form className="auth-card" onSubmit={submit}>
          <div className="auth-mobile-brand"><DroneMark size={40} /><b>VYOM DRONES</b></div>
          <span className="eyebrow">Pre-flight check</span>
          <h1>Sign in to the ERP</h1>
          <p className="muted">
            Use the company email and password your director or manager set up for you.
          </p>
          <label className="field">
            <span>Email</span>
            <input type="email" placeholder="you@vyomdrones.com" value={email}
                   onChange={(e) => setEmail(e.target.value)} required autoFocus />
          </label>
          <label className="field">
            <span>Password</span>
            <input type="password" placeholder="Your password" value={password}
                   onChange={(e) => setPassword(e.target.value)} required />
          </label>
          <button className="btn primary block" disabled={busy}>
            {busy ? 'Contacting tower…' : 'Take off  →'}
          </button>
          {error && <div className="inline err">{error}</div>}
          <p className="auth-hint">No account yet? Ask a director or your manager to add you.</p>
        </form>
        <p className="auth-foot">© {new Date().getFullYear()} Vyom Drones Pvt Ltd</p>
      </section>
    </div>
  );
}

/* -------------------------------------------------------------- punch UI */

function PunchTable({ punches, showName, empty = 'No punches recorded yet.' }) {
  if (!punches.length) return <Empty title={empty} hint="Punches appear here after the device syncs." />;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            {showName && <th>Crew member</th>}
            <th>Date</th>
            <th>Time</th>
            <th>Verified by</th>
          </tr>
        </thead>
        <tbody>
          {punches.map((p, i) => (
            <tr key={i}>
              {showName && (
                <td><div className="who"><Avatar label={p.name || `#${p.enrollNumber}`} /><span>{p.name || `Crew #${p.enrollNumber}`}<small>#{p.enrollNumber}</small></span></div></td>
              )}
              <td className="mono dim">{dayOf(p.time)}</td>
              <td className="mono strong">{hhmm(p.time)}</td>
              <td><ModeTag label={p.verifyLabel} /></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function HourChart({ punches }) {
  const hours = Array.from({ length: 17 }, (_, i) => i + 6); // 06..22
  const counts = hours.map((h) => punches.filter((p) => Number(p.time.slice(11, 13)) === h).length);
  const max = Math.max(1, ...counts);
  const nowH = new Date().getHours();
  return (
    <div className="hours">
      {hours.map((h, i) => (
        <div key={h} className={`hour ${h === nowH ? 'now' : ''}`} title={`${counts[i]} punch(es) at ${h}:00`}>
          <div className="bar-track"><div className="bar" style={{ height: `${(counts[i] / max) * 100}%` }}>{counts[i] > 0 && <em>{counts[i]}</em>}</div></div>
          <span>{String(h).padStart(2, '0')}</span>
        </div>
      ))}
    </div>
  );
}

/* ------------------------------------------------------------- employee */

function EmployeeView({ me, ctx }) {
  const [tab, setTab] = useState('home');
  const [punches, setPunches] = useState([]);
  const [error, setError] = useState(null);
  const linked = me.enrollNumber != null;

  const load = useCallback(async () => {
    if (!linked) return;
    try {
      const d = await call('GET', '/api/attendance/my', null, me.token);
      setPunches(d.punches || []);
      setError(null);
    } catch (e) { setError(e.message); }
  }, [me.token, linked]);

  const [mbos, setMbos] = useState([]);
  const loadMbo = useCallback(async () => {
    try { setMbos((await call('GET', '/api/mbo/my', null, me.token)).threads || []); }
    catch (e) { setError(e.message); }
  }, [me.token]);

  useEffect(() => {
    load(); loadMbo();
    const t = setInterval(() => { load(); loadMbo(); }, 30000); // live-ish updates
    return () => clearInterval(t);
  }, [load, loadMbo]);
  const mboWaiting = mbos.filter((t) => t.status === 'open' && t.waitingOn === 'employee').length;

  const days = useMemo(() => groupByDay(punches), [punches]);
  const today = days[todayStr()] || [];
  const onDuty = today.length % 2 === 1;
  const todayMin = today.length ? minutesBetween(today[0].time, onDuty ? new Date().toLocaleString('sv') : today[today.length - 1].time) : 0;

  const week = Array.from({ length: 7 }, (_, i) => {
    const d = new Date(); d.setDate(d.getDate() - (6 - i));
    const key = d.toLocaleDateString('sv');
    const list = days[key] || [];
    return {
      key, label: d.toLocaleDateString('en-GB', { weekday: 'short' }), date: d.getDate(),
      minutes: list.length > 1 ? minutesBetween(list[0].time, list[list.length - 1].time) : 0,
      present: list.length > 0,
    };
  });
  const weekMin = week.reduce((s, d) => s + d.minutes, 0);
  const presentDays = week.filter((d) => d.present).length;
  const name = me.name || me.email.split('@')[0];

  return (
    <Shell me={me} ctx={ctx} tab={tab} setTab={setTab}
           nav={[
             { key: 'home', label: 'My dashboard', icon: 'radar' },
             { key: 'log', label: 'Flight log', icon: 'log' },
             { key: 'leave', label: 'Leave', icon: 'calendar' },
             { key: 'mbo', label: 'MBOs', icon: 'chat', count: mboWaiting },
           ]}
           subtitle={{ home: greeting(), leave: 'Leave tracking', mbo: 'Objectives & discussions' }[tab] || 'Personal history'}
           title={{ home: name, leave: 'My leave', mbo: 'My MBOs' }[tab] || 'My flight log'}>
      <Toasts error={error} onClose={() => setError(null)} />

      {tab === 'home' && <LeaveBalance me={me} />}
      {tab === 'leave' && <EmployeeLeave me={me} />}
      {tab === 'mbo' && <EmployeeMbo me={me} threads={mbos} reload={loadMbo} />}

      {!linked && (tab === 'home' || tab === 'log') && (
        <section className="panel">
          <Empty title="Not linked to a device user yet"
                 hint="A director needs to link your account to your profile on the attendance terminal before your punches show up here." />
        </section>
      )}

      {linked && tab === 'home' && (<>
        <section className={`duty ${onDuty ? 'on' : ''}`}>
          <div className="duty-ring">
            <svg viewBox="0 0 120 120">
              <circle cx="60" cy="60" r="52" className="ring-bg" />
              <circle cx="60" cy="60" r="52" className="ring-fg"
                      style={{ strokeDashoffset: 327 - 327 * Math.min(1, todayMin / 480) }} />
            </svg>
            <div><b>{fmtDuration(todayMin)}</b><span>of 8h shift</span></div>
          </div>
          <div className="duty-info">
            <span className={`pill ${onDuty ? 'live' : today.length ? 'done' : 'idle'}`}>
              {onDuty ? '● On base' : today.length ? 'Signed out' : 'Not checked in'}
            </span>
            <h2>{onDuty ? 'You are cleared for operations.' : today.length ? 'Shift logged for today.' : 'Awaiting your first punch today.'}</h2>
            <div className="duty-facts">
              <div><span>First in</span><b>{today[0] ? hhmm(today[0].time) : '--:--'}</b></div>
              <div><span>Last punch</span><b>{today.length ? hhmm(today[today.length - 1].time) : '--:--'}</b></div>
              <div><span>Punches</span><b>{today.length}</b></div>
              <div><span>Device ID</span><b>#{me.enrollNumber ?? '—'}</b></div>
            </div>
          </div>
        </section>

        <div className="grid-2">
          <section className="panel">
            <div className="panel-head"><h3>Last 7 days</h3><span className="muted">{fmtDuration(weekMin)} · {presentDays}/7 days</span></div>
            <div className="week">
              {week.map((d) => (
                <div key={d.key} className={`wday ${d.key === todayStr() ? 'today' : ''} ${d.present ? 'present' : ''}`}>
                  <div className="wbar"><div style={{ height: `${Math.min(100, (d.minutes / 600) * 100)}%` }} /></div>
                  <b>{d.date}</b><span>{d.label}</span>
                </div>
              ))}
            </div>
          </section>
          <section className="panel">
            <div className="panel-head"><h3>Today's timeline</h3></div>
            {today.length === 0 ? <Empty title="No punches today" hint="Punch in at the hangar terminal to start your shift." /> : (
              <ol className="timeline">
                {today.map((p, i) => (
                  <li key={i} className={i % 2 === 0 ? 'in' : 'out'}>
                    <b className="mono">{hhmm(p.time)}</b>
                    <span>{i % 2 === 0 ? 'Checked in' : 'Checked out'}</span>
                    <ModeTag label={p.verifyLabel} />
                  </li>
                ))}
              </ol>
            )}
          </section>
        </div>
      </>)}

      {linked && tab === 'log' && (
        <section className="panel">
          {Object.keys(days).length === 0 ? <Empty title="No history yet" hint="Your punches will appear here once synced." /> : (
            <div className="table-wrap">
              <table>
                <thead><tr><th>Date</th><th>First in</th><th>Last out</th><th>Duration</th><th>Punches</th></tr></thead>
                <tbody>
                  {Object.entries(days).map(([d, list]) => (
                    <tr key={d}>
                      <td className="strong">{new Date(d).toLocaleDateString('en-GB', { weekday: 'short', day: '2-digit', month: 'short' })}</td>
                      <td className="mono">{hhmm(list[0].time)}</td>
                      <td className="mono">{list.length > 1 ? hhmm(list[list.length - 1].time) : '—'}</td>
                      <td className="mono strong">{fmtDuration(list.length > 1 ? minutesBetween(list[0].time, list[list.length - 1].time) : 0)}</td>
                      <td><span className="count">{list.length}</span></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
    </Shell>
  );
}

/* --------------------------------------------------------------- people */


function RoleChips({ roles }) {
  return (
    <div className="role-chips">
      {ROLE_ORDER.filter((r) => roles.includes(r)).map((r) => (
        <span key={r} className={`role ${r}`}>{ROLE_META[r].label}</span>
      ))}
    </div>
  );
}

function ReportsTo({ lines }) {
  if (!lines?.length) return <span className="dim">—</span>;
  return (
    <div className="reports">
      {lines.map((l, i) => (
        <span key={i}>{l.managerName}{l.project && <small>{l.project}</small>}</span>
      ))}
    </div>
  );
}

/**
 * Create or edit a person. Directors can grant any role and edit everything;
 * managers can only add employees and must name at least one reporting manager.
 */
function PersonForm({ me, isDirector, person, onClose, onSaved }) {
  const editing = !!person;
  const [opts, setOpts] = useState({ managers: [], deviceUsers: {} });
  const [name, setName] = useState(person?.name || '');
  const [email, setEmail] = useState(person?.email || '');
  const [password, setPassword] = useState('');
  const [enroll, setEnroll] = useState(person?.enrollNumber ?? '');
  const [roles, setRoles] = useState(person?.roles || ['employee']);
  const [status, setStatus] = useState(person?.status || 'active');
  const [lines, setLines] = useState(
    person ? person.reportsTo.map((l) => ({ managerId: l.managerId, project: l.project }))
      : isDirector ? [] : [{ managerId: me.id, project: '' }]);
  const [newPassword, setNewPassword] = useState('');
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    call('GET', '/api/people/options', null, me.token).then(setOpts).catch((e) => setError(e.message));
  }, [me.token]);

  const managers = opts.managers.filter((m) => m.id !== person?.id);
  const deviceUsers = { ...opts.deviceUsers };
  if (person?.enrollNumber != null) deviceUsers[person.enrollNumber] = `${person.name || person.email} (current)`;
  // managers must name a reporting manager; directors may leave it empty (e.g. for other directors)
  const needsManager = !isDirector;

  const toggleRole = (r) => setRoles((rs) => rs.includes(r) ? rs.filter((x) => x !== r) : [...rs, r]);
  const setLine = (i, patch) => setLines((ls) => ls.map((l, j) => j === i ? { ...l, ...patch } : l));

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError(null);
    try {
      const reportsTo = lines.filter((l) => l.managerId).map((l) => ({ managerId: Number(l.managerId), project: l.project.trim() }));
      if (needsManager && reportsTo.length === 0) throw new Error('pick at least one reporting manager');
      const body = {
        name, enrollNumber: enroll === '' ? null : Number(enroll), reportsTo,
        ...(isDirector ? { roles } : {}),
      };
      if (editing) {
        await call('PUT', `/api/people/${person.id}`, { ...body, status }, me.token);
      } else {
        await call('POST', '/api/people', { ...body, email, password }, me.token);
      }
      onSaved(editing ? `Updated ${name}` : `Added ${name}`);
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  };

  const resetPassword = async () => {
    setBusy(true); setError(null); setNotice(null);
    try {
      await call('POST', `/api/people/${person.id}/password`, { password: newPassword }, me.token);
      setNewPassword('');
      setNotice('Password reset. They have been signed out everywhere.');
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  };

  return (
    <Modal title={editing ? (person.name || person.email) : isDirector ? 'Add a person' : 'Add an employee'}
           eyebrow={editing ? 'Edit person' : 'New account'} onClose={onClose}>
      <form className="person-form" onSubmit={submit}>
        <div className="form-grid">
          <label className="field">
            <span>Full name</span>
            <input value={name} onChange={(e) => setName(e.target.value)} required autoFocus />
          </label>
          <label className="field">
            <span>Email</span>
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required disabled={editing} />
          </label>
          {!editing && (
            <label className="field">
              <span>Initial password</span>
              <input type="text" value={password} onChange={(e) => setPassword(e.target.value)}
                     required minLength={6} placeholder="Minimum 6 characters — share it with them" />
            </label>
          )}
          <label className="field">
            <span>Device user (attendance terminal)</span>
            <select value={enroll} onChange={(e) => setEnroll(e.target.value)}>
              <option value="">Not linked yet</option>
              {Object.entries(deviceUsers).map(([n, label]) => (
                <option key={n} value={n}>{label} (#{n})</option>
              ))}
            </select>
          </label>
        </div>

        {isDirector && (
          <fieldset className="field">
            <span>Roles</span>
            <div className="role-picks">
              {ROLE_ORDER.map((r) => (
                <label key={r} className={`role-pick ${roles.includes(r) ? 'on' : ''} ${r === 'employee' ? 'locked' : ''}`}>
                  <input type="checkbox" checked={roles.includes(r)} disabled={r === 'employee'}
                         onChange={() => toggleRole(r)} />
                  <Icon name={ROLE_META[r].icon} size={15} />{ROLE_META[r].label}
                </label>
              ))}
            </div>
            <small className="dim">Everyone is an employee. Add Manager or Director on top; they switch between dashboards.</small>
          </fieldset>
        )}

        <fieldset className="field">
          <span>Reporting managers {needsManager && <em className="req">required</em>}</span>
          <div className="lines">
            {lines.length === 0 && <small className="dim">No reporting manager yet.</small>}
            {lines.map((l, i) => (
              <div className="line" key={i}>
                <select value={l.managerId} onChange={(e) => setLine(i, { managerId: e.target.value })} required>
                  <option value="">Pick a manager…</option>
                  {managers.map((m) => (
                    <option key={m.id} value={m.id}>{m.name || m.email}{m.id === me.id ? ' (you)' : ''}</option>
                  ))}
                </select>
                <input placeholder="Project (optional)" value={l.project} onChange={(e) => setLine(i, { project: e.target.value })} />
                <button type="button" className="icon-btn" title="Remove" onClick={() => setLines(lines.filter((_, j) => j !== i))}>×</button>
              </div>
            ))}
            <button type="button" className="btn ghost small" onClick={() => setLines([...lines, { managerId: '', project: '' }])}>
              <Icon name="plus" size={15} />Add reporting manager
            </button>
          </div>
          <small className="dim">One person can report to different managers on different projects.</small>
        </fieldset>

        {editing && (
          <label className="field">
            <span>Status</span>
            <select value={status} onChange={(e) => setStatus(e.target.value)}>
              <option value="active">Active — can sign in</option>
              <option value="disabled">Disabled — signed out, can't sign in</option>
            </select>
          </label>
        )}

        {error && <div className="inline err">{error}</div>}
        {notice && <div className="inline ok">{notice}</div>}

        <div className="modal-actions">
          <button type="button" className="btn ghost" onClick={onClose}>Cancel</button>
          <button className="btn primary" disabled={busy}>{busy ? 'Saving…' : editing ? 'Save changes' : 'Create account'}</button>
        </div>
      </form>

      {editing && (
        <div className="reset">
          <span className="eyebrow">Reset password</span>
          <div className="line">
            <input type="text" placeholder="New password (min 6)" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} />
            <button className="btn danger" disabled={busy || newPassword.length < 6} onClick={resetPassword}>Reset</button>
          </div>
        </div>
      )}
    </Modal>
  );
}

function PasswordDialog({ me, onClose }) {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState(null);
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);

  const submit = async (e) => {
    e.preventDefault();
    setError(null);
    if (next !== confirm) { setError("new passwords don't match"); return; }
    setBusy(true);
    try {
      await call('POST', '/api/auth/password', { current, next }, me.token);
      setDone(true);
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  };

  return (
    <Modal title="Change password" eyebrow="Your account" onClose={onClose}>
      {done ? (<>
        <div className="inline ok">Password changed.</div>
        <div className="modal-actions"><button className="btn primary" onClick={onClose}>Done</button></div>
      </>) : (
        <form className="person-form" onSubmit={submit}>
          <label className="field"><span>Current password</span>
            <input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} required autoFocus /></label>
          <label className="field"><span>New password</span>
            <input type="password" value={next} onChange={(e) => setNext(e.target.value)} required minLength={6} /></label>
          <label className="field"><span>Confirm new password</span>
            <input type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} required minLength={6} /></label>
          {error && <div className="inline err">{error}</div>}
          <div className="modal-actions">
            <button type="button" className="btn ghost" onClick={onClose}>Cancel</button>
            <button className="btn primary" disabled={busy}>{busy ? 'Saving…' : 'Change password'}</button>
          </div>
        </form>
      )}
    </Modal>
  );
}

/* ------------------------------------------------- director & manager */

/**
 * Operations dashboard. Directors see everyone and manage all people;
 * managers see only the people who report to them and can add employees.
 */
function OpsView({ me, ctx, scope }) {
  const isDirector = scope === 'director';
  const [tab, setTab] = useState('today');
  const [today, setToday] = useState([]);
  const [all, setAll] = useState([]);
  const [people, setPeople] = useState([]);
  const [notice, setNotice] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [q, setQ] = useState('');
  const [day, setDay] = useState('');
  const [pq, setPq] = useState('');
  const [editing, setEditing] = useState(null); // null | 'new' | person
  const [leaves, setLeaves] = useState([]);
  const [leaveBalanceOf, setLeaveBalanceOf] = useState(null); // (accountId, year) -> balances
  const [mbos, setMbos] = useState([]);

  const loadMbo = useCallback(async () => {
    try {
      const d = await call('GET', isDirector ? '/api/mbo/all' : '/api/mbo/team', null, me.token);
      setMbos(d.threads || []);
    } catch (e) { setError(e.message); }
  }, [me.token, isDirector]);

  const loadLeave = useCallback(async () => {
    try {
      const d = await call('GET', isDirector ? '/api/leave/all' : '/api/leave/team', null, me.token);
      setLeaves(d.requests || []);
      setLeaveBalanceOf(() => makeBalanceOf(d.usage, d.quotas));
    } catch (e) { setError(e.message); }
  }, [me.token, isDirector]);

  const load = useCallback(async () => {
    // a picked day is fetched server-side; the unfiltered log is capped at 500 rows
    const dateQ = day ? `?date=${encodeURIComponent(day)}` : '';
    try {
      const [t, a, p] = await Promise.all(isDirector ? [
        call('GET', '/api/attendance/today', null, me.token),
        call('GET', `/api/attendance/all${dateQ}`, null, me.token),
        call('GET', '/api/people', null, me.token),
      ] : [
        call('GET', '/api/attendance/team?range=today', null, me.token),
        call('GET', `/api/attendance/team${dateQ}`, null, me.token),
        call('GET', '/api/team', null, me.token),
      ]);
      setToday(t.punches || []);
      setAll(a.punches || []);
      setPeople(isDirector ? p.people || [] : p.members || []);
      setError(null);
    } catch (e) { setError(e.message); }
  }, [me.token, isDirector, day]);

  const [agent, setAgent] = useState(null);
  const loadAgent = useCallback(async () => {
    if (!isDirector) return;
    try { setAgent(await call('GET', '/api/sync/status', null, me.token)); }
    catch { setAgent(null); }
  }, [me.token, isDirector]);

  useEffect(() => {
    load(); loadAgent(); loadLeave(); loadMbo();
    // the agent auto-syncs in the background; keep the dashboard fresh
    const t = setInterval(() => { load(); loadAgent(); loadLeave(); loadMbo(); }, 20000);
    return () => clearInterval(t);
  }, [load, loadAgent, loadLeave, loadMbo]);

  const flash = (msg) => { setNotice(msg); setTimeout(() => setNotice(null), 5000); };

  // full=true re-pulls every punch still on the device (inserts are idempotent)
  const sync = async (full = false) => {
    if (full && !window.confirm('Re-sync every punch still stored on the device? Existing punches are kept; duplicates are skipped.')) return;
    setBusy(true); setError(null);
    try {
      const r = await call('POST', '/api/sync', full ? { since: '2000-01-01T00:00:00' } : {}, me.token);
      flash(r.queued ? r.message
        : `Synced ${r.appended} new punch(es)${r.via?.startsWith('agent:') ? ` via ${r.via.slice(6)}` : ''}`);
      await load();
    } catch (e) { setError(e.message); }
    finally { setBusy(false); loadAgent(); }
  };

  const crew = useMemo(() => roster(today), [today]);
  const onBase = crew.filter((c) => c.onBase).length;
  // a manager's team list has one row per project line; count people once
  const headcount = new Set(people.map((p) => p.id)).size;
  const activeCount = new Set(people.filter((p) => p.status === 'active').map((p) => p.id)).size;
  const filtered = all.filter((p) =>
    (!day || dayOf(p.time) === day) &&
    (!q || `${p.name} ${p.enrollNumber}`.toLowerCase().includes(q.toLowerCase())));
  const shownPeople = people.filter((p) => !pq ||
    `${p.name} ${p.email} ${p.project || ''} ${(p.reportsTo || []).map((l) => `${l.managerName} ${l.project}`).join(' ')}`
      .toLowerCase().includes(pq.toLowerCase()));

  const peopleLabel = isDirector ? 'People' : 'My team';
  const titles = {
    today: [isDirector ? 'Dashboard' : 'Team today', isDirector ? 'Live operations' : 'Your reportees'],
    all: [isDirector ? 'Flight log' : 'Team log', isDirector ? 'All punches' : 'Team punches'],
    people: [peopleLabel, isDirector ? 'Roles & reporting' : 'People who report to you'],
    leave: ['Leave requests', isDirector ? 'Everyone · LTS' : 'Your team · LTS'],
    mbo: ['MBOs', isDirector ? 'Everyone · objectives & discussions' : 'Your team · objectives & discussions'],
    payroll: ['Payroll', isDirector ? 'Salaries, holidays & monthly runs' : 'Your team · finalized months'],
  };
  const mboWaiting = mbos.filter((t) => myTurn(t, me)).length;
  const pendingLeave = leaves.filter((r) => r.status === 'pending' && r.accountId !== me.id).length;

  return (
    <Shell me={me} ctx={ctx} tab={tab} setTab={setTab}
           title={titles[tab][0]} subtitle={titles[tab][1]}
           nav={[
             { key: 'today', label: isDirector ? 'Dashboard' : 'Team today', icon: 'radar' },
             { key: 'all', label: isDirector ? 'Flight log' : 'Team log', icon: 'log' },
             { key: 'people', label: peopleLabel, icon: 'crew', count: isDirector ? 0 : headcount },
             { key: 'leave', label: 'Leave requests', icon: 'calendar', count: pendingLeave },
             { key: 'mbo', label: 'MBOs', icon: 'chat', count: mboWaiting },
             { key: 'payroll', label: 'Payroll', icon: 'money' },
           ]}
           actions={isDirector ? <>
             <AgentChip agent={agent} />
             <button className="btn ghost" onClick={() => sync(true)} disabled={busy || (agent && !agent.online)}
                     title="Re-pull every punch still stored on the device">
               Sync all history
             </button>
             <button className="btn primary" onClick={() => sync()} disabled={busy || (agent && !agent.online)}>
               <Icon name="sync" />{busy ? 'Syncing…' : 'Sync device'}
             </button>
           </> : (
             <button className="btn primary" onClick={() => setEditing('new')}><Icon name="plus" />Add employee</button>
           )}>
      <Toasts notice={notice} error={error} onClose={() => setError(null)} />

      {tab === 'today' && (<>
        <div className="stats">
          <Stat label="On base now" value={onBase} unit={` / ${crew.length}`} accent="cyan" icon="plane" foot="checked in, not yet out" />
          <Stat label={isDirector ? 'Crew present today' : 'Team present today'} value={crew.length} unit={isDirector ? '' : ` / ${headcount}`}
                accent="saffron" icon="crew" foot={`${activeCount} active account(s)`} />
          <Stat label="Punches today" value={today.length} icon="clock" foot={today[0] ? `last at ${hhmm(today[0].time)}` : 'no activity yet'} />
          <Stat label={isDirector ? 'People' : 'Reportees'} value={headcount} icon="team"
                foot={<button className="link" onClick={() => setTab('people')}>{isDirector ? 'Manage people →' : 'View team →'}</button>} />
        </div>

        {isDirector && <DashboardLeave me={me} requests={leaves} balanceOf={leaveBalanceOf} reload={loadLeave} onOpenAll={() => setTab('leave')} />}

        <div className="grid-2 wide-left">
          <section className="panel">
            <div className="panel-head"><h3>Arrivals by hour</h3><span className="muted">06:00 – 22:00</span></div>
            <HourChart punches={today} />
          </section>
          <section className="panel">
            <div className="panel-head"><h3>Activity feed</h3><span className="live-dot">LIVE</span></div>
            {today.length === 0 ? <Empty title="Quiet skies" hint="No punches yet today." /> : (
              <ol className="feed">
                {today.slice(0, 8).map((p, i) => (
                  <li key={i}><Avatar label={p.name || `#${p.enrollNumber}`} /><div><b>{p.name || `Crew #${p.enrollNumber}`}</b><span>{p.verifyLabel}</span></div><time className="mono">{hhmm(p.time)}</time></li>
                ))}
              </ol>
            )}
          </section>
        </div>

        <section className="panel">
          <div className="panel-head"><h3>{isDirector ? "Today's crew" : "Today's team"}</h3><span className="muted">{crew.length} member(s)</span></div>
          {crew.length === 0 ? (
            <Empty title={!isDirector && headcount === 0 ? 'Nobody reports to you yet' : 'No one checked in yet'}
                   hint={!isDirector && headcount === 0 ? 'Add an employee, or ask a director to set you as someone’s reporting manager.' : 'Punches appear after the device syncs.'} />
          ) : (
            <div className="crew-grid">
              {crew.map((c) => (
                <div key={c.enrollNumber} className={`crew-card ${c.onBase ? 'on' : ''}`}>
                  <div className="crew-top"><Avatar label={c.name} /><div><b>{c.name}</b><span>#{c.enrollNumber}</span></div>
                    <span className={`pill ${c.onBase ? 'live' : 'done'}`}>{c.onBase ? 'On base' : 'Out'}</span></div>
                  <div className="crew-facts">
                    <div><span>In</span><b>{hhmm(c.first)}</b></div>
                    <div><span>Last</span><b>{hhmm(c.last)}</b></div>
                    <div><span>Logged</span><b>{fmtDuration(c.minutes)}</b></div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </section>
      </>)}

      {tab === 'all' && (
        <section className="panel">
          <div className="toolbar">
            <label className="search"><Icon name="search" /><input placeholder="Search crew name or ID…" value={q} onChange={(e) => setQ(e.target.value)} /></label>
            <input type="date" className="date" value={day} onChange={(e) => setDay(e.target.value)} />
            {(q || day) && <button className="btn ghost" onClick={() => { setQ(''); setDay(''); }}>Clear</button>}
            <span className="muted push">{filtered.length} record(s)</span>
          </div>
          <PunchTable punches={filtered} showName empty={all.length ? 'No matches for this filter' : 'No punches recorded yet.'} />
        </section>
      )}

      {tab === 'people' && (
        <section className="panel">
          <div className="toolbar">
            <label className="search"><Icon name="search" /><input placeholder="Search name, email, manager or project…" value={pq} onChange={(e) => setPq(e.target.value)} /></label>
            <span className="muted push">{headcount} {isDirector ? 'people' : 'reportee(s)'}</span>
            <button className="btn primary" onClick={() => setEditing('new')}><Icon name="plus" />{isDirector ? 'Add person' : 'Add employee'}</button>
          </div>
          {shownPeople.length === 0 ? (
            <Empty title={people.length ? 'No matches' : isDirector ? 'No people yet' : 'Nobody reports to you yet'}
                   hint={people.length ? 'Try a different search.' : 'Use “Add” to create the first account.'} />
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  {isDirector
                    ? <tr><th>Person</th><th>Roles</th><th>Reports to</th><th>Device user</th><th>Status</th><th /></tr>
                    : <tr><th>Person</th><th>Project</th><th>Device user</th><th>Status</th></tr>}
                </thead>
                <tbody>
                  {shownPeople.map((p, i) => (
                    <tr key={`${p.id}-${i}`}>
                      <td><div className="who"><Avatar label={p.name || p.email} /><span>{p.name || p.email.split('@')[0]}{p.id === me.id && ' (you)'}<small>{p.email}</small></span></div></td>
                      {isDirector ? (<>
                        <td><RoleChips roles={p.roles} /></td>
                        <td><ReportsTo lines={p.reportsTo} /></td>
                      </>) : <td>{p.project || <span className="dim">—</span>}</td>}
                      <td className="mono">{p.enrollNumber != null ? `#${p.enrollNumber}` : <span className="dim">not linked</span>}</td>
                      <td><span className={`pill ${p.status === 'active' ? 'live' : 'idle'}`}>{p.status}</span></td>
                      {isDirector && <td className="row-act"><button className="icon-btn neutral" title="Edit" onClick={() => setEditing(p)}><Icon name="edit" /></button></td>}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}

      {tab === 'leave' && <OpsLeave me={me} isDirector={isDirector} requests={leaves} balanceOf={leaveBalanceOf} reload={loadLeave} />}
      {tab === 'mbo' && <OpsMbo me={me} isDirector={isDirector} threads={mbos} people={people} reload={loadMbo} />}
      {tab === 'payroll' && <OpsPayroll me={me} isDirector={isDirector} people={people} />}

      {editing && (
        <PersonForm me={me} isDirector={isDirector} person={editing === 'new' ? null : editing}
                    onClose={() => setEditing(null)}
                    onSaved={async (msg) => {
                      setEditing(null); flash(msg); await load();
                      // own roles/name may have changed
                      if (editing !== 'new' && editing.id === me.id) ctx.refreshMe();
                    }} />
      )}
    </Shell>
  );
}

/* ------------------------------------------------------------------ app */

/** Remembered role if still held, else the highest one. */
function pickRole(me) {
  const saved = store.get(roleKey(me.id));
  return me.roles.includes(saved) ? saved : ROLE_ORDER.find((r) => me.roles.includes(r)) || 'employee';
}

export default function App() {
  const [me, setMe] = useState(undefined); // undefined = restoring session
  const [role, setRole] = useState('employee');
  const [pwOpen, setPwOpen] = useState(false);
  const token = me?.token || store.get(TOKEN_KEY);

  const adopt = useCallback((a, t) => {
    const next = { ...a, roles: a.roles?.length ? a.roles : ['employee'], token: t };
    setMe(next);
    setRole(pickRole(next));
  }, []);

  useEffect(() => {
    (async () => {
      const t = store.get(TOKEN_KEY);
      if (!t) { setMe(null); return; }
      try {
        adopt(await call('GET', '/api/auth/me', null, t), t);
      } catch {
        store.del(TOKEN_KEY);
        setMe(null);
      }
    })();
  }, [adopt]);

  const onLogin = (data) => {
    store.set(TOKEN_KEY, data.token);
    adopt(data, data.token);
  };

  const logout = async () => {
    await call('POST', '/api/auth/logout', null, token).catch(() => {});
    store.del(TOKEN_KEY);
    setMe(null);
  };

  const refreshMe = async () => {
    try { adopt(await call('GET', '/api/auth/me', null, token), token); }
    catch { logout(); }
  };

  if (me === undefined) return <div className="boot"><DroneMark size={64} /><span>Initialising systems…</span></div>;
  if (!me) return <AuthScreen onLogin={onLogin} />;

  const ctx = {
    role,
    switchRole: (r) => { store.set(roleKey(me.id), r); setRole(r); },
    logout,
    refreshMe,
    openPassword: () => setPwOpen(true),
  };

  return (<>
    {role === 'employee'
      ? <EmployeeView key="employee" me={me} ctx={ctx} />
      : <OpsView key={role} me={me} ctx={ctx} scope={role} />}
    {pwOpen && <PasswordDialog me={me} onClose={() => setPwOpen(false)} />}
  </>);
}
