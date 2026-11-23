/* MBOs — employee <-> manager discussions: propose, then acknowledge / deny / suggest changes. */

import { useEffect, useMemo, useState } from 'react';
import { Avatar, Empty, Icon, Modal, Stat, Toasts, call } from './ui.jsx';

const STATUS = {
  open: { label: 'Open', pill: 'warn' },
  acknowledged: { label: 'Acknowledged', pill: 'live' },
  denied: { label: 'Denied', pill: 'bad' },
};
const KIND = {
  proposal: 'Proposed',
  suggest: 'Suggested changes',
  acknowledge: 'Acknowledged',
  deny: 'Denied',
};

const fmtStamp = (ms) => new Date(ms).toLocaleString('en-GB', {
  day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit',
});
const first = (name) => (name || '').split(/[\s@]/)[0];

/** Which side of this thread the viewer is on. */
const sideOf = (t, me) => (t.employeeId === me.id ? 'employee' : 'manager');
/** Manager side is answered by the named manager or any director (no name = older thread, anyone). */
const answers = (t, me) => sideOf(t, me) === 'employee' || t.managerId == null
  || t.managerId === me.id || me.roles.includes('director');
export const myTurn = (t, me) => t.status === 'open' && t.waitingOn === sideOf(t, me) && answers(t, me);
const managerLabel = (t) => t.managerName || 'manager';
/** Rounds = proposal + every counter-suggestion. */
const rounds = (t) => t.entries.filter((e) => e.kind === 'proposal' || e.kind === 'suggest').length;
const latestProposal = (t) => [...t.entries].reverse().find((e) => e.kind === 'proposal' || e.kind === 'suggest');

function StatusPill({ t, me }) {
  const s = STATUS[t.status];
  const label = t.status !== 'open' ? s.label
    : myTurn(t, me) ? 'Your turn'
    : t.waitingOn === 'employee' ? `Waiting on ${first(t.employeeName)}` : `Waiting on ${first(managerLabel(t))}`;
  return <span className={`pill ${myTurn(t, me) ? 'warn' : t.status === 'open' ? 'done' : s.pill}`}>{label}</span>;
}

/* ---------------------------------------------------------- conversation */

function ThreadDialog({ me, thread: t, onClose, onDone }) {
  const [body, setBody] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(null);
  const mine = sideOf(t, me);
  const canAnswer = myTurn(t, me);
  const current = latestProposal(t);
  const otherName = mine === 'employee' ? first(managerLabel(t)) : first(t.employeeName);

  const act = async (action) => {
    if (action === 'deny' && !window.confirm('Deny this MBO? This closes the discussion.')) return;
    setBusy(action); setError(null);
    try {
      await call('POST', `/api/mbo/${t.id}/${action}`, { body }, me.token);
      onDone({ acknowledge: 'Acknowledged', deny: 'Denied', suggest: `Changes sent to ${otherName}` }[action]);
    } catch (err) { setError(err.message); }
    finally { setBusy(null); }
  };

  return (
    <Modal title={t.title} eyebrow={`MBO · ${t.employeeName}`} onClose={onClose}>
      <div className="mbo-meta">
        <StatusPill t={t} me={me} />
        <span className="muted">{t.employeeName} ↔ {managerLabel(t)} · started {fmtStamp(t.createdAt)} by the {t.startedBy} · {rounds(t)} round(s)</span>
      </div>

      <ol className="mbo-chat">
        {t.entries.map((e) => (
          <li key={e.id} className={`mbo-msg ${e.side} ${e.kind} ${e.side === mine ? 'mine' : ''}`}>
            <div className="mbo-msg-head">
              <Avatar label={e.authorName} />
              <span><b>{e.authorId === me.id ? 'You' : e.authorName}</b><small>{e.side === 'manager' ? 'Manager side' : 'Employee'} · {fmtStamp(e.createdAt)}</small></span>
              <span className={`mbo-kind ${e.kind}`}>{KIND[e.kind]}{e === current && t.status === 'open' ? ' · current' : ''}</span>
            </div>
            {e.body ? <p>{e.body}</p> : <p className="dim">No comment.</p>}
          </li>
        ))}
      </ol>

      {canAnswer ? (
        <div className="person-form mbo-reply">
          <label className="field">
            <span>Your response</span>
            <textarea rows={4} maxLength={4000} value={body} onChange={(e) => setBody(e.target.value)} autoFocus
                      placeholder="Acknowledge as-is (comment optional), or explain what you'd change / why you disagree." />
          </label>
          <small className="dim">
            <b>Acknowledge</b> agrees with the current proposal and closes it. <b>Suggest changes</b> sends your
            version back to {otherName}. <b>Deny</b> closes it as not agreed. Responses can't be edited later.
          </small>
          {error && <div className="inline err">{error}</div>}
          <div className="modal-actions">
            <button className="btn danger" disabled={!!busy || !body.trim()} onClick={() => act('deny')}
                    title={body.trim() ? '' : 'Write a reason to deny'}>{busy === 'deny' ? 'Saving…' : 'Deny'}</button>
            <button className="btn ghost" disabled={!!busy || !body.trim()} onClick={() => act('suggest')}
                    title={body.trim() ? '' : 'Write the changes you suggest'}>{busy === 'suggest' ? 'Sending…' : 'Suggest changes'}</button>
            <button className="btn primary" disabled={!!busy} onClick={() => act('acknowledge')}>
              <Icon name="approve" size={15} />{busy === 'acknowledge' ? 'Saving…' : 'Acknowledge'}
            </button>
          </div>
        </div>
      ) : (
        <div className="leave-sum">
          <Icon name="chat" />
          <span>{t.status !== 'open'
            ? `Closed as ${t.status}. This conversation is kept on record.`
            : `Waiting for ${t.waitingOn === 'employee' ? first(t.employeeName) : managerLabel(t)} to respond.`}</span>
        </div>
      )}
    </Modal>
  );
}

/* ---------------------------------------------------------------- start */

function NewMboDialog({ me, people, onClose, onSaved }) {
  const [employeeId, setEmployeeId] = useState('');
  const [managerId, setManagerId] = useState('');
  const [managers, setManagers] = useState(null); // employee only: who they can send to
  useEffect(() => {
    if (people) return;
    call('GET', '/api/mbo/managers', null, me.token).then((d) => {
      setManagers(d.managers || []);
      const own = (d.managers || []).filter((m) => m.relation === 'manager');
      if (own.length === 1) setManagerId(String(own[0].id)); // the usual case: one manager
    }).catch((e) => { setManagers([]); setError(e.message); });
  }, [people, me.token]);
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError(null);
    try {
      await call('POST', '/api/mbo', {
        employeeId: people ? Number(employeeId) : null,
        managerId: people ? null : Number(managerId), title, body,
      }, me.token);
      const to = managers?.find((m) => String(m.id) === managerId);
      onSaved(people ? 'MBO sent — waiting on the employee' : `MBO sent to ${to?.name || 'your manager'}`);
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  };

  return (
    <Modal title="Start an MBO" eyebrow="New discussion" onClose={onClose}>
      <form className="person-form" onSubmit={submit}>
        {people && (
          <label className="field">
            <span>With</span>
            <select value={employeeId} required onChange={(e) => setEmployeeId(e.target.value)}>
              <option value="" disabled>Pick a person…</option>
              {people.map((p) => <option key={p.id} value={p.id}>{p.name || p.email}</option>)}
            </select>
          </label>
        )}
        {!people && (
          <label className="field">
            <span>Send to</span>
            {managers && !managers.length ? (
              <div className="inline err">There's no manager or director to send it to yet — ask a director to set your reporting manager.</div>
            ) : (
              <select value={managerId} required disabled={!managers} onChange={(e) => setManagerId(e.target.value)}>
                <option value="" disabled>{managers ? 'Pick a manager…' : 'Loading…'}</option>
                {[['manager', 'Your managers'], ['director', 'Directors']].map(([rel, label]) => {
                  const list = (managers || []).filter((m) => m.relation === rel);
                  return list.length > 0 && (
                    <optgroup key={rel} label={label}>
                      {list.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
                    </optgroup>
                  );
                })}
              </select>
            )}
          </label>
        )}
        <label className="field">
          <span>Objective</span>
          <input maxLength={200} required value={title} onChange={(e) => setTitle(e.target.value)}
                 placeholder="e.g. Lead the Q1 survey-drone rollout" />
        </label>
        <label className="field">
          <span>Details</span>
          <textarea rows={5} maxLength={4000} required value={body} onChange={(e) => setBody(e.target.value)}
                    placeholder="What's expected, how it's measured, by when." />
        </label>
        <small className="dim">
          {people ? 'They can acknowledge it, deny it, or suggest changes.'
            : 'They can acknowledge it, deny it, or suggest changes.'} The whole conversation is kept on record.
        </small>
        {error && <div className="inline err">{error}</div>}
        <div className="modal-actions">
          <button type="button" className="btn ghost" onClick={onClose}>Cancel</button>
          <button className="btn primary" disabled={busy || !title.trim() || !body.trim() || (people ? !employeeId : !managerId)}>
            {busy ? 'Sending…' : 'Send'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

/* ----------------------------------------------------------------- list */

function ThreadTable({ me, threads, showName, onOpen, empty }) {
  if (!threads.length) return <Empty title={empty} hint="Start an MBO to open a discussion." />;
  return (
    <div className="table-wrap">
      <table className="leave-table">
        <thead>
          <tr>
            {showName && <th>Person</th>}
            <th>Objective</th>
            <th>Manager</th>
            <th>Rounds</th>
            <th>Status</th>
            <th>Last activity</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {threads.map((t) => {
            const last = t.entries[t.entries.length - 1];
            return (
              <tr key={t.id} className="mbo-row" onClick={() => onOpen(t.id)}>
                {showName && (
                  <td><div className="who"><Avatar label={t.employeeName} /><span>{t.employeeName}<small>{t.employeeEmail}</small></span></div></td>
                )}
                <td>
                  <b>{t.title}</b>
                  {last && <small className="sub reason" title={last.body}>{last.authorName}: {KIND[last.kind].toLowerCase()}{last.body ? ` — ${last.body}` : ''}</small>}
                </td>
                <td>{t.managerId === me.id ? 'You' : managerLabel(t)}</td>
                <td className="mono strong">{rounds(t)}</td>
                <td><StatusPill t={t} me={me} /></td>
                <td className="mono dim">{fmtStamp(t.updatedAt)}</td>
                <td className="row-act">
                  <button className={`btn small ${myTurn(t, me) ? 'primary' : 'ghost'}`}>{myTurn(t, me) ? 'Respond' : 'View'}</button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function useDialogs(threads, reload) {
  const [openId, setOpenId] = useState(null);
  const [starting, setStarting] = useState(false);
  const [notice, setNotice] = useState(null);
  const [error, setError] = useState(null);
  const flash = (m) => { setNotice(m); setTimeout(() => setNotice(null), 5000); };
  // derive from the latest list so a reload refreshes an open conversation
  const open = threads.find((t) => t.id === openId) || null;
  const done = (m) => { flash(m); reload(); };
  return { open, setOpenId, starting, setStarting, notice, error, setError, flash, done };
}

/* ------------------------------------------------------------- employee */

/** Employee "MBOs" tab: own discussions with the manager side. */
export function EmployeeMbo({ me, threads, reload }) {
  const d = useDialogs(threads, reload);
  const waiting = threads.filter((t) => myTurn(t, me));
  const count = (s) => threads.filter((t) => t.status === s).length;

  return (<>
    <Toasts notice={d.notice} error={d.error} onClose={() => d.setError(null)} />
    <div className="stats">
      <Stat label="Waiting on you" value={waiting.length} accent={waiting.length ? 'saffron' : ''} icon="chat"
            foot={waiting.length ? 'your manager asked for a response' : 'nothing to answer'} />
      <Stat label="Waiting on manager" value={count('open') - waiting.length} icon="clock" foot="you've responded" />
      <Stat label="Acknowledged" value={count('acknowledged')} accent="cyan" icon="approve" foot="agreed objectives" />
      <Stat label="Denied" value={count('denied')} icon="log" foot="closed without agreement" />
    </div>

    <section className="panel">
      <div className="panel-head">
        <h3>My MBOs</h3>
        <button className="btn primary" onClick={() => d.setStarting(true)}><Icon name="plus" />Start an MBO</button>
      </div>
      <ThreadTable me={me} threads={threads} onOpen={d.setOpenId} empty="No MBOs yet" />
    </section>

    {d.starting && <NewMboDialog me={me} onClose={() => d.setStarting(false)}
                                 onSaved={(m) => { d.setStarting(false); d.done(m); }} />}
    {d.open && <ThreadDialog me={me} thread={d.open} onClose={() => d.setOpenId(null)}
                             onDone={(m) => { d.setOpenId(null); d.done(m); }} />}
  </>);
}

/* ------------------------------------------------------------ managers */

/* ---------------------------------------------------------- performance */

const DAY = 864e5;
const PERIODS = {
  '90d': { label: 'Last 3 months', since: () => Date.now() - 90 * DAY },
  '12m': { label: 'Last 12 months', since: () => Date.now() - 365 * DAY },
  year: { label: 'This year', since: () => new Date(new Date().getFullYear(), 0, 1).getTime() },
  all: { label: 'All time', since: () => 0 },
};
const BADGES = {
  agreed: { label: 'Most agreed', title: 'Most objectives agreed in this period' },
  initiative: { label: 'Most initiative', title: 'Raised the most MBOs themselves' },
  quick: { label: 'Quickest replies', title: 'Fastest average reply to their manager' },
};
/** A wait longer than this shows as overdue. */
const OVERDUE = 7 * DAY;

const fmtWait = (ms) => ms == null ? '—'
  : ms < 3600e3 ? `${Math.max(1, Math.round(ms / 60e3))}m`
  : ms < 2 * DAY ? `${Math.round(ms / 3600e3)}h`
  : `${Math.round(ms / DAY)}d`;

/**
 * Per-person numbers from their MBO threads. avgReply is how long the
 * employee took to answer a manager-side message, on average.
 */
function scorecard(threads) {
  const by = new Map();
  for (const t of threads) {
    const r = by.get(t.employeeId) || {
      id: t.employeeId, name: t.employeeName, email: t.employeeEmail, threads: [],
      total: 0, acknowledged: 0, denied: 0, open: 0, initiated: 0, agreedRounds: 0,
      replyMs: 0, replies: 0, waiting: 0, oldestWait: 0, last: 0, badges: [],
    };
    r.threads.push(t);
    r.total++; r[t.status]++;
    if (t.startedBy === 'employee') r.initiated++;
    if (t.status === 'acknowledged') r.agreedRounds += rounds(t);
    for (let i = 1; i < t.entries.length; i++) {
      const [prev, cur] = [t.entries[i - 1], t.entries[i]];
      if (prev.side === 'manager' && cur.side === 'employee') { r.replyMs += cur.createdAt - prev.createdAt; r.replies++; }
    }
    if (t.status === 'open' && t.waitingOn === 'employee') {
      r.waiting++;
      r.oldestWait = Math.max(r.oldestWait, Date.now() - t.entries[t.entries.length - 1].createdAt);
    }
    r.last = Math.max(r.last, t.updatedAt);
    by.set(t.employeeId, r);
  }
  const rows = [...by.values()].map((r) => {
    const closed = r.acknowledged + r.denied;
    return {
      ...r,
      rate: closed ? r.acknowledged / closed : null,
      avgReply: r.replies ? r.replyMs / r.replies : null,
      avgRounds: r.acknowledged ? r.agreedRounds / r.acknowledged : null,
    };
  });
  // badges go to the leader(s) on each measure; ties share it
  const award = (key, value, better) => {
    const vals = rows.map(value).filter((v) => v != null && v > 0);
    if (!vals.length) return;
    const best = better === 'low' ? Math.min(...vals) : Math.max(...vals);
    rows.forEach((r) => { if (value(r) === best) r.badges.push(key); });
  };
  award('agreed', (r) => r.acknowledged);
  award('initiative', (r) => r.initiated);
  award('quick', (r) => (r.replies >= 2 ? r.avgReply : null), 'low');
  return rows.sort((a, b) => b.acknowledged - a.acknowledged || (b.rate ?? -1) - (a.rate ?? -1) || a.name.localeCompare(b.name));
}

function Badges({ keys }) {
  return keys.map((k) => <span key={k} className={`perf-badge ${k}`} title={BADGES[k].title}><Icon name="star" size={11} />{BADGES[k].label}</span>);
}

function RateBar({ rate }) {
  if (rate == null) return <span className="dim">—</span>;
  const pct = Math.round(rate * 100);
  return <span className="perf-rate"><span className="track"><i style={{ width: `${pct}%` }} /></span><b>{pct}%</b></span>;
}

/** One person's performance card: tiles + their MBOs in the period. */
function PersonDialog({ me, row: r, period, onOpenThread, onClose }) {
  return (
    <Modal title={r.name} eyebrow={`Performance · ${PERIODS[period].label}`} onClose={onClose}>
      {r.badges.length > 0 && <div className="perf-badges"><Badges keys={r.badges} /></div>}
      <div className="perf-tiles">
        <div><span>Objectives agreed</span><b>{r.acknowledged}</b><small>of {r.total} MBO(s)</small></div>
        <div><span>Agreement rate</span><b>{r.rate == null ? '—' : `${Math.round(r.rate * 100)}%`}</b><small>{r.denied} denied</small></div>
        <div><span>Raised themselves</span><b>{r.initiated}</b><small>initiative</small></div>
        <div><span>Avg reply time</span><b>{fmtWait(r.avgReply)}</b><small>{r.replies} repl{r.replies === 1 ? 'y' : 'ies'}</small></div>
        <div><span>Rounds to agree</span><b>{r.avgRounds == null ? '—' : r.avgRounds.toFixed(1)}</b><small>lower = quicker alignment</small></div>
        <div className={r.oldestWait > OVERDUE ? 'warn' : ''}><span>Waiting on them</span><b>{r.waiting}</b><small>{r.waiting ? `oldest ${fmtWait(r.oldestWait)}` : 'nothing pending'}</small></div>
      </div>
      <h4 className="perf-sub">MBOs in this period</h4>
      <ul className="perf-list">
        {r.threads.map((t) => (
          <li key={t.id} onClick={() => onOpenThread(t.id)}>
            <span><b>{t.title}</b><small>with {t.managerId === me.id ? 'you' : managerLabel(t)} · {rounds(t)} round(s) · {fmtStamp(t.updatedAt)}</small></span>
            <StatusPill t={t} me={me} />
          </li>
        ))}
      </ul>
      <small className="dim">Figures cover MBOs active in the period. Badges go to whoever leads each measure among the people you can see.</small>
    </Modal>
  );
}

/** Per-person performance table — the view for awards and promotion reviews. */
function Performance({ me, threads, onOpenThread }) {
  const [period, setPeriod] = useState('12m');
  const [picked, setPicked] = useState(null);
  const rows = useMemo(() => {
    const since = PERIODS[period].since();
    return scorecard(threads.filter((t) => t.updatedAt >= since));
  }, [threads, period]);
  const row = rows.find((r) => r.id === picked);

  return (
    <section className="panel">
      <div className="panel-head">
        <h3>Performance</h3>
        <select className="date" value={period} onChange={(e) => setPeriod(e.target.value)} style={{ width: 'auto' }}>
          {Object.entries(PERIODS).map(([k, p]) => <option key={k} value={k}>{p.label}</option>)}
        </select>
      </div>
      {!rows.length ? <Empty title="No MBOs in this period" hint="Each person's record builds up as discussions close." /> : (
        <div className="table-wrap">
          <table className="leave-table">
            <thead>
              <tr><th>Person</th><th>Agreed</th><th>Agreement rate</th><th>Raised themselves</th><th>Avg reply</th><th>Waiting on them</th><th>Last activity</th></tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id} className="mbo-row" onClick={() => setPicked(r.id)}>
                  <td>
                    <div className="who"><Avatar label={r.name} /><span>{r.name}<small>{r.email}</small></span></div>
                    {r.badges.length > 0 && <div className="perf-badges"><Badges keys={r.badges} /></div>}
                  </td>
                  <td className="mono strong">{r.acknowledged}<small className="sub">of {r.total}</small></td>
                  <td><RateBar rate={r.rate} /></td>
                  <td className="mono">{r.initiated}</td>
                  <td className="mono">{fmtWait(r.avgReply)}</td>
                  <td className="mono">{r.waiting
                    ? <span className={r.oldestWait > OVERDUE ? 'perf-overdue' : ''}>{r.waiting}<small className="sub">oldest {fmtWait(r.oldestWait)}</small></span>
                    : <span className="dim">0</span>}</td>
                  <td className="mono dim">{fmtStamp(r.last)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {row && <PersonDialog me={me} row={row} period={period} onClose={() => setPicked(null)}
                            onOpenThread={(id) => { setPicked(null); onOpenThread(id); }} />}
    </section>
  );
}

/**
 * Manager/director "MBOs" tab. threads are the reportees' (manager) or
 * everyone else's (director); people is the list they can start one with.
 */
export function OpsMbo({ me, isDirector, threads, people, reload }) {
  const d = useDialogs(threads, reload);
  const [status, setStatus] = useState('');
  const [q, setQ] = useState('');
  const waiting = threads.filter((t) => myTurn(t, me)).sort((a, b) => a.updatedAt - b.updatedAt);
  const count = (s) => threads.filter((t) => t.status === s).length;
  const othersTurn = threads.filter((t) => t.status === 'open' && t.waitingOn === 'manager' && !myTurn(t, me)).length;
  const shown = threads.filter((t) => (!status || t.status === status) &&
    (!q || `${t.employeeName} ${t.employeeEmail} ${t.title}`.toLowerCase().includes(q.toLowerCase())));
  // one option per person (a manager's team list has a row per project line), never yourself
  const pickable = useMemo(() => {
    const seen = new Map();
    for (const p of people) if (p.id !== me.id && !seen.has(p.id)) seen.set(p.id, p);
    return [...seen.values()].sort((a, b) => (a.name || a.email).localeCompare(b.name || b.email));
  }, [people, me.id]);

  return (<>
    <Toasts notice={d.notice} error={d.error} onClose={() => d.setError(null)} />
    <div className="stats">
      <Stat label="Waiting on you" value={waiting.length} accent={waiting.length ? 'saffron' : ''} icon="chat"
            foot={waiting.length ? `oldest from ${first(waiting[0].employeeName)}` : 'all caught up'} />
      <Stat label="Waiting on employee" value={count('open') - waiting.length - othersTurn} icon="clock"
            foot={othersTurn ? `+${othersTurn} with another manager` : 'you\u2019ve responded'} />
      <Stat label="Acknowledged" value={count('acknowledged')} accent="cyan" icon="approve" foot="agreed objectives" />
      <Stat label="Denied" value={count('denied')} icon="log" foot={isDirector ? 'across everyone' : 'across your team'} />
    </div>

    {waiting.length > 0 && (
      <section className="panel">
        <div className="panel-head"><h3>Waiting on your response</h3><span className="muted">{waiting.length} open</span></div>
        <div className="leave-cards">
          {waiting.map((t) => {
            const cur = latestProposal(t);
            return (
              <div key={t.id} className="leave-card">
                <div className="who"><Avatar label={t.employeeName} /><span>{t.employeeName}<small>{cur && `${KIND[cur.kind].toLowerCase()} ${fmtStamp(cur.createdAt)}`}</small></span></div>
                <b>{t.title}</b>
                {cur && <p title={cur.body}>{cur.body}</p>}
                <div className="leave-act">
                  <button className="btn primary small" onClick={() => d.setOpenId(t.id)}><Icon name="chat" size={15} />Respond</button>
                </div>
              </div>
            );
          })}
        </div>
      </section>
    )}

    <Performance me={me} threads={threads} onOpenThread={d.setOpenId} />

    <section className="panel">
      <div className="toolbar">
        <label className="search"><Icon name="search" /><input placeholder="Search name or objective…" value={q} onChange={(e) => setQ(e.target.value)} /></label>
        <select className="date" value={status} onChange={(e) => setStatus(e.target.value)} style={{ width: 'auto' }}>
          <option value="">All statuses</option>
          {Object.entries(STATUS).map(([k, s]) => <option key={k} value={k}>{s.label}</option>)}
        </select>
        <span className="muted push">{shown.length} MBO(s)</span>
        <button className="btn primary" onClick={() => d.setStarting(true)} disabled={!pickable.length}><Icon name="plus" />Start an MBO</button>
      </div>
      <ThreadTable me={me} threads={shown} showName onOpen={d.setOpenId} empty={threads.length ? 'No matches' : 'No MBOs yet'} />
    </section>

    {d.starting && <NewMboDialog me={me} people={pickable} onClose={() => d.setStarting(false)}
                                 onSaved={(m) => { d.setStarting(false); d.done(m); }} />}
    {d.open && <ThreadDialog me={me} thread={d.open} onClose={() => d.setOpenId(null)}
                             onDone={(m) => { d.setOpenId(null); d.done(m); }} />}
  </>);
}
