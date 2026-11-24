/* Payroll — fixed monthly salary, cut for missed hours. Directors run it; managers read their team. */

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Avatar, Empty, Icon, Modal, Stat, Toasts, call, fmtDuration } from './ui.jsx';

const STATUS = {
  draft: { label: 'Draft', pill: 'warn' },
  finalized: { label: 'Finalized', pill: 'done' },
  paid: { label: 'Paid', pill: 'live' },
};
const DAY_TYPE = {
  work: 'Working day', sunday: 'Sunday', holiday: 'Holiday', leave: 'Leave',
  before: 'Before start', future: 'Not counted yet',
};

/* ------------------------------------------------------------- helpers */

export const money = (n) => `₹${Number(n || 0).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
export const hrs = (n) => `${Math.round(Number(n || 0) * 100) / 100} h`;
export const monthLabel = (ym) => new Date(`${ym}-01T00:00:00Z`)
  .toLocaleDateString('en-GB', { month: 'long', year: 'numeric', timeZone: 'UTC' });
const prevMonth = () => { const d = new Date(); d.setDate(1); d.setMonth(d.getMonth() - 1); return d.toLocaleDateString('sv').slice(0, 7); };
const fmtDay = (s) => new Date(`${s}T00:00:00Z`).toLocaleDateString('en-GB', { weekday: 'short', day: '2-digit', month: 'short', timeZone: 'UTC' });
const fmtStamp = (ms) => (ms ? new Date(ms).toLocaleString('en-GB', { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—');
const totalHours = (l) => Math.max(0, l.missedHours + l.correctionHours);

function StatusPill({ status }) {
  const s = STATUS[status] || { label: 'Not generated', pill: 'idle' };
  return <span className={`pill ${s.pill}`}>{s.label}</span>;
}

/* ------------------------------------------------------- day breakdown */

/** Day-by-day view of one person's month, as stored when it was generated. */
export function DaysDialog({ me, line, onClose }) {
  const [days, setDays] = useState(null);
  const [error, setError] = useState(null);
  useEffect(() => {
    call('GET', `/api/payroll/runs/${line.month}/${line.accountId}/days`, null, me.token)
      .then((d) => setDays(d.days)).catch((e) => setError(e.message));
  }, [me.token, line.month, line.accountId]);

  const short = days ? days.filter((d) => d.missedMin > 0) : [];
  return (
    <Modal title={line.name} eyebrow={`Attendance · ${monthLabel(line.month)}`} onClose={onClose}>
      {error && <div className="inline err">{error}</div>}
      {!days ? !error && <p className="muted">Loading…</p> : (<>
        <div className="pay-days-sum">
          <span><b>{line.workingDays}</b> working days counted</span>
          <span><b>{short.length}</b> short</span>
          <span><b>{hrs(line.missedHours)}</b> missed</span>
          {!line.linked && <span className="warn">Not linked to the device — no punches</span>}
        </div>
        <div className="table-wrap pay-days">
          <table className="leave-table">
            <thead><tr><th>Day</th><th>Type</th><th>In</th><th>Out</th><th>Worked</th><th>Needed</th><th>Missed</th></tr></thead>
            <tbody>
              {days.map((d) => (
                <tr key={d.date} className={`pay-day ${d.type} ${d.missedMin ? 'short' : ''}`}>
                  <td className="mono">{fmtDay(d.date)}</td>
                  <td>{DAY_TYPE[d.type]}{d.note && d.type !== 'sunday' ? <small className="sub">{d.note}</small> : null}</td>
                  <td className="mono">{d.first || '—'}</td>
                  <td className="mono">{d.last && d.last !== d.first ? d.last : '—'}</td>
                  <td className="mono">{d.spanMin ? fmtDuration(d.spanMin) : '—'}</td>
                  <td className="mono dim">{d.type === 'work' ? fmtDuration(d.requiredMin) : '—'}</td>
                  <td className="mono strong">{d.missedMin ? fmtDuration(d.missedMin) : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <small className="dim">Needed is a 9 h first-to-last punch span (1 h lunch included), less any approved leave.
          Up to 10 minutes short is forgiven; more is rounded up to the next half hour, at most 8 h a day.</small>
      </>)}
    </Modal>
  );
}

/* ----------------------------------------------------------------- csv */

const CSV_COLS = [
  ['Month', (l) => l.month], ['Name', (l) => l.name], ['Email', (l) => l.email],
  ['Linked to device', (l) => (l.linked ? 'yes' : 'no')], ['Working days', (l) => l.workingDays],
  ['Missed hours', (l) => l.missedHours], ['Correction hours', (l) => l.correctionHours],
  ['Correction note', (l) => l.correctionNote], ['Basic', (l) => l.basic], ['HRA', (l) => l.hra],
  ['Other allowance', (l) => l.otherAllowance], ['Monthly gross', (l) => l.fullGross],
  ['Gross this month', (l) => l.gross], ['Day rate', (l) => l.dayRate], ['Hour rate', (l) => l.hourRate],
  ['Cut', (l) => l.cut], ['Net pay', (l) => l.net],
];
const csvCell = (v) => {
  const s = String(v ?? '');
  // a leading = + - @ in text would run as a formula in Excel
  const safe = /^[=+\-@]/.test(s) && Number.isNaN(Number(s)) ? `'${s}` : s;
  return /[",\n]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe;
};

/** Builds the month's CSV in the browser and downloads it. */
function downloadCsv(month, lines) {
  const rows = [CSV_COLS.map(([h]) => h), ...lines.map((l) => CSV_COLS.map(([, f]) => f(l)))];
  const text = rows.map((r) => r.map(csvCell).join(',')).join('\r\n');
  const blob = new Blob([`﻿${text}`], { type: 'text/csv;charset=utf-8' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `payroll-${month}.csv`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}

/* ---------------------------------------------------------- run dialogs */

function CorrectionDialog({ me, line, onClose, onDone }) {
  const [hours, setHours] = useState(String(line.correctionHours || 0));
  const [note, setNote] = useState(line.correctionNote || '');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const h = Number(hours) || 0;
  const after = Math.max(0, line.missedHours + h);
  const net = Math.max(0, line.gross - Math.round(line.hourRate * after * 100) / 100);

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError(null);
    try {
      await call('POST', `/api/payroll/runs/${line.month}/${line.accountId}/correction`, { hours: h, note }, me.token);
      onDone(`Correction saved for ${line.name}`);
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  };

  return (
    <Modal title="Correct missed hours" eyebrow={`${line.name} · ${monthLabel(line.month)}`} onClose={onClose}>
      <form className="person-form" onSubmit={submit}>
        <p className="muted">Attendance shows <b>{hrs(line.missedHours)}</b> missed. Use a minus number to forgive hours
          (e.g. a forgotten punch), a plus number to add them.</p>
        <div className="form-grid">
          <label className="field">
            <span>Correction (hours)</span>
            <input type="number" step="0.5" min="-300" max="300" value={hours} onChange={(e) => setHours(e.target.value)} autoFocus />
          </label>
          <div className="field"><span>Result</span>
            <div className="leave-sum"><span><b>{hrs(after)}</b> cut · net <b>{money(net)}</b></span></div>
          </div>
        </div>
        <label className="field">
          <span>Reason {h === 0 && <em className="dim">(optional when 0)</em>}</span>
          <textarea rows={3} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)}
                    placeholder="e.g. Forgot to punch out on 12 Sep — confirmed by Mona." />
        </label>
        <small className="dim">Shown on the employee's payslip. Kept when the month is regenerated.</small>
        {error && <div className="inline err">{error}</div>}
        <div className="modal-actions">
          <button type="button" className="btn ghost" onClick={onClose}>Cancel</button>
          <button className="btn primary" disabled={busy || (h !== 0 && !note.trim())}>{busy ? 'Saving…' : 'Save correction'}</button>
        </div>
      </form>
    </Modal>
  );
}

function PaidDialog({ me, month, onClose, onDone }) {
  const [paidOn, setPaidOn] = useState(new Date().toLocaleDateString('sv'));
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError(null);
    try { await call('POST', `/api/payroll/runs/${month}/paid`, { paidOn }, me.token); onDone(`${monthLabel(month)} marked paid`); }
    catch (err) { setError(err.message); }
    finally { setBusy(false); }
  };
  return (
    <Modal title="Mark as paid" eyebrow={monthLabel(month)} onClose={onClose}>
      <form className="person-form" onSubmit={submit}>
        <label className="field"><span>Paid on</span>
          <input type="date" required value={paidOn} onChange={(e) => setPaidOn(e.target.value)} />
        </label>
        {error && <div className="inline err">{error}</div>}
        <div className="modal-actions">
          <button type="button" className="btn ghost" onClick={onClose}>Cancel</button>
          <button className="btn primary" disabled={busy}>{busy ? 'Saving…' : 'Mark paid'}</button>
        </div>
      </form>
    </Modal>
  );
}

/* ----------------------------------------------------------- run panel */

/** Lines table for a month. editable = draft and director. */
function LinesTable({ lines, editable, onDays, onCorrect }) {
  if (!lines.length) return <Empty title="No payroll lines" hint="Give people a salary, then generate the month." />;
  return (
    <div className="table-wrap">
      <table className="leave-table">
        <thead>
          <tr><th>Person</th><th>Working days</th><th>Missed</th><th>Correction</th><th>Gross</th><th>Cut</th><th>Net</th><th /></tr>
        </thead>
        <tbody>
          {lines.map((l) => (
            <tr key={l.accountId}>
              <td>
                <div className="who"><Avatar label={l.name} /><span>{l.name}<small>{l.email}</small></span></div>
                {!l.linked && <small className="sub pay-flag">not linked to the device</small>}
              </td>
              <td className="mono">{l.workingDays}</td>
              <td className="mono">{hrs(l.missedHours)}</td>
              <td className="mono">{l.correctionHours ? <>{l.correctionHours > 0 ? '+' : ''}{hrs(l.correctionHours)}
                <small className="sub reason" title={l.correctionNote}>{l.correctionNote}</small></> : <span className="dim">—</span>}</td>
              <td className="mono">{money(l.gross)}{l.gross < l.fullGross && <small className="sub">prorated of {money(l.fullGross)}</small>}</td>
              <td className="mono">{l.cut ? `− ${money(l.cut)}` : <span className="dim">—</span>}<small className="sub">{hrs(totalHours(l))} × {money(l.hourRate)}</small></td>
              <td className="mono strong">{money(l.net)}</td>
              <td className="row-act">
                <div className="leave-act">
                  <button className="btn ghost small" onClick={() => onDays(l)}>Days</button>
                  {editable && <button className="btn ghost small" onClick={() => onCorrect(l)}><Icon name="edit" size={14} />Correct</button>}
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function RunPanel({ me, isDirector, flash, setError }) {
  const [runs, setRuns] = useState([]);
  const [month, setMonth] = useState(prevMonth());
  const [lines, setLines] = useState([]);
  const [busy, setBusy] = useState(false);
  const [days, setDays] = useState(null);
  const [correcting, setCorrecting] = useState(null);
  const [paying, setPaying] = useState(false);

  const loadRuns = useCallback(async () => {
    try {
      const r = (await call('GET', '/api/payroll/runs', null, me.token)).runs || [];
      setRuns(r);
      // managers only have locked months: start on the newest one
      if (!isDirector && r.length && !r.some((x) => x.month === month)) setMonth(r[0].month);
    } catch (e) { setError(e.message); }
  }, [me.token, isDirector]); // eslint-disable-line react-hooks/exhaustive-deps
  const loadLines = useCallback(async () => {
    try { setLines((await call('GET', `/api/payroll/runs/${month}`, null, me.token)).lines || []); }
    catch (e) { setError(e.message); }
  }, [me.token, month, setError]);
  useEffect(() => { loadRuns(); }, [loadRuns]);
  useEffect(() => { loadLines(); }, [loadLines]);

  const run = runs.find((r) => r.month === month);
  const status = run?.status;
  const reload = () => { loadRuns(); loadLines(); };
  const act = async (what, msg, confirmText) => {
    if (confirmText && !window.confirm(confirmText)) return;
    setBusy(true); setError(null);
    try { const r = await call('POST', `/api/payroll/runs/${month}/${what}`, {}, me.token); flash(msg(r)); reload(); }
    catch (e) { setError(e.message); }
    finally { setBusy(false); }
  };
  const sum = (k) => lines.reduce((s, l) => s + l[k], 0);

  return (<>
    <section className="panel">
      <div className="toolbar">
        {isDirector
          ? <input type="month" className="date" value={month} onChange={(e) => e.target.value && setMonth(e.target.value)} style={{ width: 'auto' }} />
          : (
            <select className="date" value={month} onChange={(e) => setMonth(e.target.value)} style={{ width: 'auto' }} disabled={!runs.length}>
              {!runs.length && <option value={month}>No finalized months yet</option>}
              {runs.map((r) => <option key={r.month} value={r.month}>{monthLabel(r.month)}</option>)}
            </select>
          )}
        <StatusPill status={status} />
        {run?.finalizedAt && <span className="muted">finalized {fmtStamp(run.finalizedAt)} by {run.finalizedBy || '—'}</span>}
        {run?.paidOn && <span className="muted">· paid on {run.paidOn}</span>}
        <span className="push" />
        {isDirector && (!status || status === 'draft') && (
          <button className="btn ghost" disabled={busy} onClick={() => act('generate', (r) => `${monthLabel(month)}: ${r.people} line(s) ${status ? 'regenerated' : 'generated'}`)}>
            <Icon name="sync" />{status ? 'Regenerate' : 'Generate'}
          </button>
        )}
        {isDirector && status === 'draft' && (
          <button className="btn primary" disabled={busy || !lines.length}
                  onClick={() => act('finalize', () => `${monthLabel(month)} finalized`, `Finalize ${monthLabel(month)}? The numbers are locked after this and employees can see their payslips.`)}>
            <Icon name="approve" />Finalize
          </button>
        )}
        {isDirector && status === 'finalized' && <button className="btn primary" onClick={() => setPaying(true)}>Mark paid</button>}
        {isDirector && lines.length > 0 && (
          <button className="btn ghost" onClick={() => downloadCsv(month, lines)} title="Download this month for the bank or accountant">
            Export CSV
          </button>
        )}
      </div>

      <div className="stats">
        <Stat label="People" value={lines.length} icon="team" foot={lines.some((l) => !l.linked) ? `${lines.filter((l) => !l.linked).length} not linked to the device` : 'all linked'} />
        <Stat label="Gross" value={money(sum('gross'))} icon="money" foot="before cuts" />
        <Stat label="Cut for missed hours" value={money(sum('cut'))} accent={sum('cut') ? 'saffron' : ''} icon="clock"
              foot={`${hrs(lines.reduce((s, l) => s + totalHours(l), 0))} in total`} />
        <Stat label="Net payout" value={money(sum('net'))} accent="cyan" icon="approve" foot={monthLabel(month)} />
      </div>

      {status === 'draft' && <div className="inline pay-note">Draft — numbers update each time you regenerate (new punches, leave or holidays). Corrections are kept.</div>}
      <LinesTable lines={lines} editable={isDirector && status === 'draft'} onDays={setDays} onCorrect={setCorrecting} />
    </section>

    {days && <DaysDialog me={me} line={days} onClose={() => setDays(null)} />}
    {correcting && <CorrectionDialog me={me} line={correcting} onClose={() => setCorrecting(null)}
                                     onDone={(m) => { setCorrecting(null); flash(m); loadLines(); }} />}
    {paying && <PaidDialog me={me} month={month} onClose={() => setPaying(false)} onDone={(m) => { setPaying(false); flash(m); reload(); }} />}
  </>);
}

/* ------------------------------------------------------------ salaries */

function SalaryDialog({ me, people, person, onClose, onDone }) {
  const today = new Date().toLocaleDateString('sv');
  const [accountId, setAccountId] = useState(person ? String(person.accountId) : '');
  const [basic, setBasic] = useState(person ? String(person.basic) : '');
  const [hra, setHra] = useState(person ? String(person.hra) : '0');
  const [other, setOther] = useState(person ? String(person.otherAllowance) : '0');
  const [from, setFrom] = useState(person ? today : `${today.slice(0, 7)}-01`);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const gross = (Number(basic) || 0) + (Number(hra) || 0) + (Number(other) || 0);
  const day = gross / 30;

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError(null);
    try {
      await call('POST', '/api/payroll/salaries', {
        accountId: Number(accountId), basic: Number(basic) || 0, hra: Number(hra) || 0,
        otherAllowance: Number(other) || 0, effectiveFrom: from,
      }, me.token);
      onDone('Salary saved');
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  };

  return (
    <Modal title={person ? 'Change salary' : 'Set salary'} eyebrow={person ? person.name : 'Payroll'} onClose={onClose}>
      <form className="person-form" onSubmit={submit}>
        {!person && (
          <label className="field"><span>Employee</span>
            <select value={accountId} required onChange={(e) => setAccountId(e.target.value)}>
              <option value="" disabled>Pick a person…</option>
              {people.map((p) => <option key={p.id} value={p.id}>{p.name || p.email}</option>)}
            </select>
          </label>
        )}
        <div className="form-grid">
          <label className="field"><span>Basic / month (₹)</span>
            <input type="number" min="0" step="0.01" required value={basic} onChange={(e) => setBasic(e.target.value)} autoFocus />
          </label>
          <label className="field"><span>HRA / month (₹)</span>
            <input type="number" min="0" step="0.01" value={hra} onChange={(e) => setHra(e.target.value)} />
          </label>
          <label className="field"><span>Other allowance / month (₹)</span>
            <input type="number" min="0" step="0.01" value={other} onChange={(e) => setOther(e.target.value)} />
          </label>
          <label className="field"><span>Effective from</span>
            <input type="date" required value={from} onChange={(e) => setFrom(e.target.value)} />
          </label>
        </div>
        <div className="leave-sum"><Icon name="money" /><span>
          Gross <b>{money(gross)}</b> / month · in a 30-day month that's <b>{money(day)}</b> a day, <b>{money(day / 8)}</b> an hour missed.
        </span></div>
        <small className="dim">A change adds a new row — the old salary stays in the history. The first salary's date is the
          joining date: a mid-month start is paid from that day.</small>
        {error && <div className="inline err">{error}</div>}
        <div className="modal-actions">
          <button type="button" className="btn ghost" onClick={onClose}>Cancel</button>
          <button className="btn primary" disabled={busy || !accountId || gross <= 0}>{busy ? 'Saving…' : 'Save salary'}</button>
        </div>
      </form>
    </Modal>
  );
}

function SalariesPanel({ me, people, flash, setError }) {
  const [rows, setRows] = useState([]);
  const [editing, setEditing] = useState(null); // null | 'new' | current row
  const [history, setHistory] = useState(null);
  const load = useCallback(async () => {
    try { setRows((await call('GET', '/api/payroll/salaries', null, me.token)).salaries || []); }
    catch (e) { setError(e.message); }
  }, [me.token, setError]);
  useEffect(() => { load(); }, [load]);

  // rows come newest-first per person: the first one seen is the current salary
  const current = useMemo(() => {
    const seen = new Map();
    for (const r of rows) if (!seen.has(r.accountId)) seen.set(r.accountId, { ...r, changes: 0 });
    for (const r of rows) seen.get(r.accountId).changes++;
    return [...seen.values()];
  }, [rows]);
  const pickable = useMemo(() => {
    const map = new Map();
    for (const p of people) if (p.status === 'active' && !map.has(p.id)) map.set(p.id, p);
    return [...map.values()].sort((a, b) => (a.name || a.email).localeCompare(b.name || b.email));
  }, [people]);
  const without = pickable.filter((p) => !current.some((c) => c.accountId === p.id));

  return (
    <section className="panel">
      <div className="panel-head">
        <h3>Salaries</h3>
        <button className="btn primary" onClick={() => setEditing('new')} disabled={!without.length}
                title={without.length ? '' : 'Everyone has a salary — use Change on a row'}><Icon name="plus" />Set salary</button>
      </div>
      {without.length > 0 && <div className="inline pay-note">{without.length} active people have no salary yet and won't be in payroll: {without.slice(0, 6).map((p) => p.name || p.email).join(', ')}{without.length > 6 ? '…' : ''}</div>}
      {!current.length ? <Empty title="No salaries yet" hint="Set each person's monthly salary to include them in payroll." /> : (
        <div className="table-wrap">
          <table className="leave-table">
            <thead><tr><th>Person</th><th>Basic</th><th>HRA</th><th>Other</th><th>Gross / month</th><th>Since</th><th /></tr></thead>
            <tbody>
              {current.map((r) => (
                <tr key={r.accountId}>
                  <td><div className="who"><Avatar label={r.name} /><span>{r.name}<small>{r.email}</small></span></div></td>
                  <td className="mono">{money(r.basic)}</td>
                  <td className="mono">{money(r.hra)}</td>
                  <td className="mono">{money(r.otherAllowance)}</td>
                  <td className="mono strong">{money(r.gross)}</td>
                  <td className="mono dim">{r.effectiveFrom}</td>
                  <td className="row-act"><div className="leave-act">
                    {r.changes > 1 && <button className="btn ghost small" onClick={() => setHistory(r.accountId)}>History ({r.changes})</button>}
                    <button className="btn ghost small" onClick={() => setEditing(r)}><Icon name="edit" size={14} />Change</button>
                  </div></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {editing && <SalaryDialog me={me} people={without} person={editing === 'new' ? null : editing}
                                onClose={() => setEditing(null)} onDone={(m) => { setEditing(null); flash(m); load(); }} />}
      {history && (
        <Modal title={rows.find((r) => r.accountId === history).name} eyebrow="Salary history" onClose={() => setHistory(null)}>
          <ul className="perf-list">
            {rows.filter((r) => r.accountId === history).map((r) => (
              <li key={r.id}><span><b>{money(r.gross)} / month</b>
                <small>from {r.effectiveFrom} · basic {money(r.basic)} · HRA {money(r.hra)} · other {money(r.otherAllowance)} · set by {r.createdBy || '—'} {fmtStamp(r.createdAt)}</small></span></li>
            ))}
          </ul>
        </Modal>
      )}
    </section>
  );
}

/* ------------------------------------------------------------ holidays */

function HolidaysPanel({ me, flash, setError }) {
  const [list, setList] = useState([]);
  const [day, setDay] = useState('');
  const [name, setName] = useState('');
  const [year, setYear] = useState(String(new Date().getFullYear()));
  const load = useCallback(async () => {
    try { setList((await call('GET', '/api/payroll/holidays', null, me.token)).holidays || []); }
    catch (e) { setError(e.message); }
  }, [me.token, setError]);
  useEffect(() => { load(); }, [load]);

  const add = async (e) => {
    e.preventDefault();
    try { setList((await call('POST', '/api/payroll/holidays', { day, name }, me.token)).holidays); flash(`Holiday added: ${name}`); setDay(''); setName(''); }
    catch (err) { setError(err.message); }
  };
  const remove = async (h) => {
    if (!window.confirm(`Remove ${h.name} (${h.day})?`)) return;
    try { setList((await call('DELETE', `/api/payroll/holidays/${h.day}`, null, me.token)).holidays); flash('Holiday removed'); }
    catch (err) { setError(err.message); }
  };
  const years = [...new Set([String(new Date().getFullYear()), ...list.map((h) => h.day.slice(0, 4))])].sort();
  const shown = list.filter((h) => h.day.startsWith(year));

  return (
    <section className="panel">
      <div className="panel-head">
        <h3>Holidays</h3>
        <select className="date" value={year} onChange={(e) => setYear(e.target.value)} style={{ width: 'auto' }}>
          {years.map((y) => <option key={y} value={y}>{y}</option>)}
        </select>
      </div>
      <form className="toolbar" onSubmit={add}>
        <input type="date" className="date" required value={day} onChange={(e) => setDay(e.target.value)} style={{ width: 'auto' }} />
        <label className="search"><input placeholder="Holiday name, e.g. Diwali" required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} /></label>
        <button className="btn primary"><Icon name="plus" />Add holiday</button>
      </form>
      {!shown.length ? <Empty title={`No holidays in ${year}`} hint="Holidays are paid and never count as missed hours. Sundays already are." /> : (
        <ul className="perf-list">
          {shown.map((h) => (
            <li key={h.day}><span><b>{h.name}</b><small>{fmtDay(h.day)} {h.day.slice(0, 4)}</small></span>
              <button className="btn ghost small" onClick={() => remove(h)}>Remove</button></li>
          ))}
        </ul>
      )}
      <small className="dim">Changes affect draft months after you regenerate them; finalized months stay as they are.</small>
    </section>
  );
}

/* ---------------------------------------------------------------- tabs */

/** Director: run / salaries / holidays. Manager: read-only locked months of their reportees. */
export function OpsPayroll({ me, isDirector, people }) {
  const [section, setSection] = useState('run');
  const [notice, setNotice] = useState(null);
  const [error, setError] = useState(null);
  const flash = useCallback((m) => { setNotice(m); setTimeout(() => setNotice(null), 5000); }, []);

  return (<>
    <Toasts notice={notice} error={error} onClose={() => setError(null)} />
    {isDirector && (
      <div className="role-picks pay-sections">
        {[['run', 'Monthly payroll'], ['salaries', 'Salaries'], ['holidays', 'Holidays']].map(([k, label]) => (
          <button key={k} className={`role-pick ${section === k ? 'on' : ''}`} onClick={() => setSection(k)}>{label}</button>
        ))}
      </div>
    )}
    {section === 'run' && <RunPanel me={me} isDirector={isDirector} flash={flash} setError={setError} />}
    {isDirector && section === 'salaries' && <SalariesPanel me={me} people={people} flash={flash} setError={setError} />}
    {isDirector && section === 'holidays' && <HolidaysPanel me={me} flash={flash} setError={setError} />}
    {!isDirector && <p className="dim pay-foot">You see your reportees' pay once a director finalizes the month.</p>}
  </>);
}

/* ------------------------------------------------------------ payslips */

function Payslip({ p, onDays }) {
  const prorated = p.gross < p.fullGross;
  return (
    <article className="payslip">
      <header className="payslip-head">
        <div><b>VYOM DRONES</b><span>Payslip · {monthLabel(p.month)}</span></div>
        <StatusPill status={p.status} />
      </header>
      <div className="payslip-who">
        <div><span>Employee</span><b>{p.name}</b><small>{p.email}</small></div>
        <div><span>Working days counted</span><b>{p.workingDays}</b><small>{p.paidOn ? `paid on ${p.paidOn}` : 'not paid yet'}</small></div>
      </div>
      <div className="payslip-cols">
        <section>
          <h4>Earnings</h4>
          <dl>
            <dt>Basic</dt><dd>{money(p.basic)}</dd>
            <dt>HRA</dt><dd>{money(p.hra)}</dd>
            <dt>Other allowance</dt><dd>{money(p.otherAllowance)}</dd>
            {prorated && <><dt>Joined mid-month</dt><dd>− {money(p.fullGross - p.gross)}</dd></>}
            <dt className="tot">Gross</dt><dd className="tot">{money(p.gross)}</dd>
          </dl>
        </section>
        <section>
          <h4>Deductions</h4>
          <dl>
            <dt>Missed hours</dt><dd>{hrs(p.missedHours)}</dd>
            {p.correctionHours !== 0 && <><dt>Correction</dt><dd>{p.correctionHours > 0 ? '+' : ''}{hrs(p.correctionHours)}</dd></>}
            <dt>Rate</dt><dd>{hrs(totalHours(p))} × {money(p.hourRate)}</dd>
            <dt className="tot">Cut</dt><dd className="tot">− {money(p.cut)}</dd>
          </dl>
          {p.correctionNote && <p className="payslip-note">“{p.correctionNote}”</p>}
        </section>
      </div>
      <footer className="payslip-net"><span>Net pay</span><b>{money(p.net)}</b></footer>
      <small className="dim payslip-rule">Day rate = gross ÷ days in {monthLabel(p.month)} = {money(p.dayRate)}; hour rate = day rate ÷ 8.
        Sundays, holidays and approved leave are paid.</small>
      <div className="leave-act no-print">
        <button className="btn ghost small" onClick={() => onDays(p)}><Icon name="calendar" size={15} />Attendance</button>
        <button className="btn primary small" onClick={() => window.print()}><Icon name="log" size={15} />Print / save PDF</button>
      </div>
    </article>
  );
}

/** Employee "Payslips" tab: own finalized / paid months. */
export function EmployeePayslips({ me }) {
  const [list, setList] = useState(null);
  const [month, setMonth] = useState(null);
  const [days, setDays] = useState(null);
  const [error, setError] = useState(null);
  useEffect(() => {
    call('GET', '/api/payroll/my', null, me.token)
      .then((d) => { setList(d.payslips || []); if (d.payslips?.length) setMonth(d.payslips[0].month); })
      .catch((e) => { setList([]); setError(e.message); });
  }, [me.token]);
  const p = list?.find((x) => x.month === month);

  return (<>
    <Toasts error={error} onClose={() => setError(null)} />
    {!list ? <p className="muted">Loading…</p> : !list.length ? (
      <section className="panel"><Empty title="No payslips yet" hint="A payslip appears here once a director finalizes the month." /></section>
    ) : (<>
      <div className="stats">
        <Stat label="Latest net pay" value={money(list[0].net)} accent="cyan" icon="money" foot={monthLabel(list[0].month)} />
        <Stat label="Latest cut" value={money(list[0].cut)} accent={list[0].cut ? 'saffron' : ''} icon="clock"
              foot={`${hrs(totalHours(list[0]))} missed`} />
        <Stat label="Payslips" value={list.length} icon="log" foot="finalized months" />
      </div>
      <section className="panel">
        <div className="panel-head">
          <h3>Payslip</h3>
          <select className="date" value={month} onChange={(e) => setMonth(e.target.value)} style={{ width: 'auto' }}>
            {list.map((x) => <option key={x.month} value={x.month}>{monthLabel(x.month)} · {money(x.net)}</option>)}
          </select>
        </div>
        {p && <Payslip p={p} onDays={setDays} />}
      </section>
    </>)}
    {days && <DaysDialog me={me} line={days} onClose={() => setDays(null)} />}
  </>);
}
