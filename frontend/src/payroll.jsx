/* Payroll — fixed monthly salary, cut for missed hours. Directors run it; managers read their team. */

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Avatar, Empty, Icon, Modal, Stat, Toasts, call, fmtDuration } from './ui.jsx';
import { MyOvertime, OT_RULE, OvertimeHistory, OvertimePanel, otDaysLabel, pendingOvertimeCount } from './overtime.jsx';

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
  const ot = days ? days.filter((d) => d.otDays > 0) : [];
  return (
    <Modal title={line.name} eyebrow={`Attendance · ${monthLabel(line.month)}`} onClose={onClose}>
      {error && <div className="inline err">{error}</div>}
      {!days ? !error && <p className="muted">Loading…</p> : (<>
        <div className="pay-days-sum">
          <span><b>{line.workingDays}</b> working days counted</span>
          <span><b>{short.length}</b> short</span>
          <span><b>{hrs(line.missedHours)}</b> missed</span>
          {ot.length > 0 && <span><b>{otDaysLabel(line.overtimeDays)}</b> overtime on {ot.length} day{ot.length === 1 ? '' : 's'}</span>}
          {!line.linked && <span className="warn">Not linked to the device — no punches</span>}
        </div>
        <div className="table-wrap pay-days">
          <table className="leave-table">
            <thead><tr><th>Day</th><th>Type</th><th>In</th><th>Out</th><th>Worked</th><th>Needed</th><th>Missed</th><th>Overtime</th></tr></thead>
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
                  <td className="mono">{d.otDays ? <>{otDaysLabel(d.otDays)}<small className="sub">{fmtDuration(d.otMin)} over</small></> : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <small className="dim">Needed is an 8:30 h first-to-last punch span (lunch included), less any approved leave.
          Up to 10 minutes short is forgiven; more is rounded up to the next half hour, at most 8 h a day.
          Overtime shows only once approved. {OT_RULE}</small>
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
  ['Cut', (l) => l.cut], ['Overtime days', (l) => l.overtimeDays], ['Overtime pay', (l) => l.overtimePay],
  ['Net pay', (l) => l.net],
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
function LinesTable({ lines, editable, onDays, onCorrect, onPerson }) {
  if (!lines.length) return <Empty title="No payroll lines" hint="Give people a salary, then generate the month." />;
  return (
    <div className="table-wrap">
      <table className="leave-table">
        <thead>
          <tr><th>Person</th><th>Working days</th><th>Missed</th><th>Correction</th><th>Gross</th><th>Cut</th><th>Overtime</th><th>Net</th><th /></tr>
        </thead>
        <tbody>
          {lines.map((l) => (
            <tr key={l.accountId}>
              <td>
                <button className="link who" title="Pay and overtime history" onClick={() => onPerson(l)}>
                  <Avatar label={l.name} /><span>{l.name}<small>{l.email}</small></span></button>
                {!l.linked && <small className="sub pay-flag">not linked to the device</small>}
              </td>
              <td className="mono">{l.workingDays}</td>
              <td className="mono">{hrs(l.missedHours)}</td>
              <td className="mono">{l.correctionHours ? <>{l.correctionHours > 0 ? '+' : ''}{hrs(l.correctionHours)}
                <small className="sub reason" title={l.correctionNote}>{l.correctionNote}</small></> : <span className="dim">—</span>}</td>
              <td className="mono">{money(l.gross)}{l.gross < l.fullGross && <small className="sub">prorated of {money(l.fullGross)}</small>}</td>
              <td className="mono">{l.cut ? `− ${money(l.cut)}` : <span className="dim">—</span>}<small className="sub">{hrs(totalHours(l))} × {money(l.hourRate)}</small></td>
              <td className="mono">{l.overtimePay ? <>+ {money(l.overtimePay)}<small className="sub">{otDaysLabel(l.overtimeDays)} × {money(l.dayRate)}</small></>
                : <span className="dim">—</span>}</td>
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

function RunPanel({ me, isDirector, flash, setError, onPerson }) {
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
        <Stat label="People" value={lines.length} icon="team"
              foot={!lines.length ? 'no one in this month yet' : lines.some((l) => !l.linked) ? `${lines.filter((l) => !l.linked).length} not linked to the device` : 'all linked to the device'} />
        <Stat label="Gross" value={money(sum('gross'))} icon="money" foot="before cuts" />
        <Stat label="Cut for missed hours" value={money(sum('cut'))} accent={sum('cut') ? 'saffron' : ''} icon="clock"
              foot={`${hrs(lines.reduce((s, l) => s + totalHours(l), 0))} in total`} />
        <Stat label="Overtime pay" value={money(sum('overtimePay'))} icon="bolt"
              foot={`${otDaysLabel(sum('overtimeDays'))} approved`} />
        <Stat label="Net payout" value={money(sum('net'))} accent="cyan" icon="approve" foot={monthLabel(month)} />
      </div>

      {isDirector && run?.overtimeChanged && (
        <div className="inline pay-note">Overtime was approved after this draft was generated — regenerate to include it.</div>
      )}
      {status === 'draft' && <div className="inline pay-note">Draft — numbers update each time you regenerate (new punches, leave or holidays). Corrections are kept.</div>}
      {!isDirector && !runs.length
        ? <Empty title="No finalized months yet" hint="Your team's pay shows here once a director finalizes a month. To put someone on payroll, use My team." />
        : <LinesTable lines={lines} editable={isDirector && status === 'draft'} onDays={setDays} onCorrect={setCorrecting}
                      onPerson={onPerson} />}
    </section>

    {days && <DaysDialog me={me} line={days} onClose={() => setDays(null)} />}
    {correcting && <CorrectionDialog me={me} line={correcting} onClose={() => setCorrecting(null)}
                                     onDone={(m) => { setCorrecting(null); flash(m); loadLines(); }} />}
    {paying && <PaidDialog me={me} month={month} onClose={() => setPaying(false)} onDone={(m) => { setPaying(false); flash(m); reload(); }} />}
  </>);
}

/* ------------------------------------------------------------ salaries */

const SAL_STATUS = { approved: 'live', pending: 'warn', rejected: 'bad' };
const SAL_LABEL = { approved: 'Approved', pending: 'Waiting for a director', rejected: 'Rejected' };

/** Set or change a salary. Directors and the person's managers can; it applies at once. */
function SalaryDialog({ me, people, person, preset, onClose, onDone }) {
  const today = new Date().toLocaleDateString('sv');
  const [accountId, setAccountId] = useState(person ? String(person.accountId) : preset ? String(preset.accountId) : '');
  const [basic, setBasic] = useState(person ? String(person.basic) : '');
  const [hra, setHra] = useState(person ? String(person.hra) : '0');
  const [other, setOther] = useState(person ? String(person.otherAllowance) : '0');
  const [from, setFrom] = useState(person ? today : preset?.effectiveFrom || `${today.slice(0, 7)}-01`);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const gross = (Number(basic) || 0) + (Number(hra) || 0) + (Number(other) || 0);
  const day = gross / 30;
  // a preset from someone else's recommendation: saving approves it
  const approving = !!preset && preset.by !== 'you';
  const verb = person ? 'Change salary' : approving ? 'Approve & set salary' : 'Set salary';

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError(null);
    try {
      const r = await call('POST', '/api/payroll/salaries', {
        accountId: Number(accountId), basic: Number(basic) || 0, hra: Number(hra) || 0,
        otherAllowance: Number(other) || 0, effectiveFrom: from,
      }, me.token);
      onDone(r.status === 'pending' ? 'Sent for approval' : approving ? `Approved — ${preset.name} is on payroll` : 'Salary saved');
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  };

  return (
    <Modal title={verb} eyebrow={person ? person.name : preset ? preset.name : 'Payroll'} onClose={onClose}>
      <form className="person-form" onSubmit={submit}>
        {!person && !preset && (
          <label className="field"><span>Employee</span>
            <select value={accountId} required onChange={(e) => setAccountId(e.target.value)}>
              <option value="" disabled>Pick a person…</option>
              {people.map((p) => <option key={p.id} value={p.id}>{p.name || p.email}</option>)}
            </select>
          </label>
        )}
        {person && <p className="muted">Now: <b>{money(person.gross)}</b> / month since {person.effectiveFrom}.</p>}
        {approving && <p className="muted">Recommended by <b>{preset.by}</b>{preset.note ? <> — “{preset.note}”</> : null}. Enter the salary to approve: they're on payroll from the effective date.</p>}
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
        <small className="dim">
          Applies straight away; directors can still change it.{' '}
          A change adds a new row — the old salary stays in the history. The first salary's date is the
          joining date: a mid-month start is paid from that day.
        </small>
        {error && <div className="inline err">{error}</div>}
        <div className="modal-actions">
          <button type="button" className="btn ghost" onClick={onClose}>Cancel</button>
          <button className="btn primary" disabled={busy || !accountId || gross <= 0}>
            {busy ? 'Saving…' : approving ? 'Approve' : 'Save salary'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function RejectDialog({ me, row, onClose, onDone }) {
  const [note, setNote] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError(null);
    try { await call('POST', `/api/payroll/salaries/${row.id}/reject`, { note }, me.token); onDone(`Rejected ${row.name}'s salary proposal`); }
    catch (err) { setError(err.message); }
    finally { setBusy(false); }
  };
  return (
    <Modal title="Reject salary proposal" eyebrow={row.name} onClose={onClose}>
      <form className="person-form" onSubmit={submit}>
        <p className="muted">{row.createdBy} proposed <b>{money(row.gross)}</b> / month from {row.effectiveFrom}.</p>
        <label className="field"><span>Reason (shown to {row.createdBy})</span>
          <textarea rows={3} maxLength={500} required value={note} onChange={(e) => setNote(e.target.value)} autoFocus
                    placeholder="e.g. Budget is ₹14,000 until the probation review." />
        </label>
        {error && <div className="inline err">{error}</div>}
        <div className="modal-actions">
          <button type="button" className="btn ghost" onClick={onClose}>Back</button>
          <button className="btn danger" disabled={busy || !note.trim()}>{busy ? 'Saving…' : 'Reject'}</button>
        </div>
      </form>
    </Modal>
  );
}

/** Manager: ask for a team member to be put on payroll; a director sets the amount. */
function RecommendDialog({ me, people, preselect, onClose, onDone }) {
  const [accountId, setAccountId] = useState(preselect ? String(preselect) : people.length === 1 ? String(people[0].id) : '');
  const [start, setStart] = useState(new Date().toLocaleDateString('sv'));
  const [note, setNote] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError(null);
    try {
      await call('POST', '/api/payroll/recommendations', { accountId: Number(accountId), startDate: start, note }, me.token);
      const who = people.find((x) => String(x.id) === accountId);
      onDone(`Recommended ${who?.name || who?.email || 'them'} for payroll`);
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  };
  return (
    <Modal title="Recommend for payroll" eyebrow="Your team" onClose={onClose}>
      <form className="person-form" onSubmit={submit}>
        <label className="field"><span>Team member</span>
          <select value={accountId} required onChange={(e) => setAccountId(e.target.value)}>
            <option value="" disabled>Pick someone from your team…</option>
            {people.map((x) => <option key={x.id} value={x.id}>{x.name || x.email}</option>)}
          </select>
        </label>
        <label className="field"><span>Suggested start date <em className="dim">(optional)</em></span>
          <input type="date" value={start} onChange={(e) => setStart(e.target.value)} />
        </label>
        <label className="field"><span>Note for the director <em className="dim">(optional)</em></span>
          <textarea rows={3} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)}
                    placeholder="e.g. Joined as drone pilot on the survey team, full-time." />
        </label>
        <small className="dim">No amount needed — a director or another of their managers sets the salary. They aren't paid until then.</small>
        {error && <div className="inline err">{error}</div>}
        <div className="modal-actions">
          <button type="button" className="btn ghost" onClick={onClose}>Cancel</button>
          <button className="btn primary" disabled={busy || !accountId}>{busy ? 'Sending…' : 'Recommend'}</button>
        </div>
      </form>
    </Modal>
  );
}

/** Director declines a recommendation, with a reason the manager sees. */
function DeclineDialog({ me, rec, onClose, onDone }) {
  const [note, setNote] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError(null);
    try { await call('POST', `/api/payroll/recommendations/${rec.id}/decline`, { note }, me.token); onDone(`Declined the recommendation for ${rec.name}`); }
    catch (err) { setError(err.message); }
    finally { setBusy(false); }
  };
  return (
    <Modal title="Decline recommendation" eyebrow={rec.name} onClose={onClose}>
      <form className="person-form" onSubmit={submit}>
        <p className="muted">{rec.recommendedBy} recommended {rec.name} for payroll{rec.startDate ? ` from ${rec.startDate}` : ''}.</p>
        <label className="field"><span>Reason (shown to {rec.recommendedBy})</span>
          <textarea rows={3} maxLength={500} required value={note} onChange={(e) => setNote(e.target.value)} autoFocus
                    placeholder="e.g. Intern — paid through the training programme, not payroll." />
        </label>
        {error && <div className="inline err">{error}</div>}
        <div className="modal-actions">
          <button type="button" className="btn ghost" onClick={onClose}>Back</button>
          <button className="btn danger" disabled={busy || !note.trim()}>{busy ? 'Saving…' : 'Decline'}</button>
        </div>
      </form>
    </Modal>
  );
}

const REC_PILL = { pending: 'warn', accepted: 'live', declined: 'bad' };
const REC_LABEL = { pending: 'Waiting for a director', accepted: 'Accepted', declined: 'Declined' };

/** Director: every salary + proposals to approve. Manager: reportees, proposing changes. */
function SalariesPanel({ me, isDirector, people, flash, setError, onPending }) {
  const [rows, setRows] = useState([]);
  const [editing, setEditing] = useState(null); // null | 'new' | current row
  const [history, setHistory] = useState(null);
  const [rejecting, setRejecting] = useState(null);
  const [recs, setRecs] = useState([]);
  const [recommending, setRecommending] = useState(false);
  const [declining, setDeclining] = useState(null);
  const [accepting, setAccepting] = useState(null); // recommendation being turned into a salary
  const [busy, setBusy] = useState(null);
  const load = useCallback(async () => {
    try {
      const [sal, rec] = await Promise.all([
        call('GET', '/api/payroll/salaries', null, me.token),
        call('GET', '/api/payroll/recommendations', null, me.token),
      ]);
      const r = sal.salaries || [], q = rec.recommendations || [];
      setRows(r);
      setRecs(q);
      onPending?.(r.filter((x) => x.status === 'pending').length + q.filter((x) => x.status === 'pending').length);
    } catch (e) { setError(e.message); }
  }, [me.token, setError, onPending]);
  useEffect(() => { load(); }, [load]);

  // rows come newest-first per person: the first approved one is the current salary
  const current = useMemo(() => {
    const seen = new Map();
    for (const r of rows) if (r.status === 'approved' && !seen.has(r.accountId)) seen.set(r.accountId, r);
    return [...seen.values()];
  }, [rows]);
  const pending = rows.filter((r) => r.status === 'pending');
  const pendingFor = (id) => pending.find((r) => r.accountId === id);
  const latestRejected = (id) => {
    const mine = rows.filter((r) => r.accountId === id && r.status !== 'approved');
    return mine[0]?.status === 'rejected' ? mine[0] : null;
  };
  const pickable = useMemo(() => {
    const map = new Map();
    for (const p of people) if ((p.status ?? 'active') === 'active' && p.id !== (isDirector ? -1 : me.id) && !map.has(p.id)) map.set(p.id, p);
    return [...map.values()].sort((a, b) => (a.name || a.email).localeCompare(b.name || b.email));
  }, [people, isDirector, me.id]);
  const recsPending = recs.filter((r) => r.status === 'pending');
  const recFor = (id) => recsPending.find((r) => r.accountId === id);
  const without = pickable.filter((p) => !current.some((c) => c.accountId === p.id) && !pendingFor(p.id) && !recFor(p.id));
  const done = (m) => {
    setEditing(null); setRejecting(null); setRecommending(false); setDeclining(null); setAccepting(null);
    flash(m); load();
  };

  const approve = async (r) => {
    setBusy(r.id);
    try { await call('POST', `/api/payroll/salaries/${r.id}/approve`, {}, me.token); done(`Approved ${r.name}'s salary`); }
    catch (e) { setError(e.message); }
    finally { setBusy(null); }
  };

  return (<>
    {isDirector && recsPending.length > 0 && (
      <section className="panel">
        <div className="panel-head"><h3>Recommended by managers</h3><span className="muted">{recsPending.length} to put on payroll</span></div>
        <div className="leave-cards">
          {recsPending.map((r) => (
            <div key={r.id} className="leave-card">
              <div className="who"><Avatar label={r.name} /><span>{r.name}<small>recommended by {r.recommendedBy || '—'} · {fmtStamp(r.createdAt)}</small></span></div>
              <b>{r.startDate ? `Start ${fmtDay(r.startDate)} ${r.startDate.slice(0, 4)}` : 'No start date given'}</b>
              {r.note ? <p title={r.note}>{r.note}</p> : <p className="dim">No note.</p>}
              <div className="leave-act">
                <button className="btn danger small" onClick={() => setDeclining(r)}>Decline</button>
                <button className="btn primary small" onClick={() => setAccepting(r)} title="Approve and set the salary"><Icon name="approve" size={15} />Approve</button>
              </div>
            </div>
          ))}
        </div>
      </section>
    )}

    {isDirector && pending.length > 0 && (
      <section className="panel">
        <div className="panel-head"><h3>Waiting for your approval</h3><span className="muted">{pending.length} proposal(s) from managers</span></div>
        <div className="leave-cards">
          {pending.map((r) => {
            const now = current.find((c) => c.accountId === r.accountId);
            return (
              <div key={r.id} className="leave-card">
                <div className="who"><Avatar label={r.name} /><span>{r.name}<small>proposed by {r.createdBy} · {fmtStamp(r.createdAt)}</small></span></div>
                <div className="pay-propose">
                  <span><small>Now</small><b>{now ? money(now.gross) : 'no salary'}</b></span>
                  <span>→</span>
                  <span><small>Proposed from {r.effectiveFrom}</small><b>{money(r.gross)}</b></span>
                </div>
                <p>Basic {money(r.basic)} · HRA {money(r.hra)} · other {money(r.otherAllowance)}</p>
                <div className="leave-act">
                  <button className="btn danger small" onClick={() => setRejecting(r)}>Reject</button>
                  <button className="btn primary small" disabled={busy === r.id} onClick={() => approve(r)}><Icon name="approve" size={15} />Approve</button>
                </div>
              </div>
            );
          })}
        </div>
      </section>
    )}

    <section className="panel">
      <div className="panel-head">
        <h3>{isDirector ? 'Salaries' : "Your team's salaries"}</h3>
        <div className="leave-act">
          {!isDirector && (
            <button className="btn ghost" onClick={() => setRecommending(true)} disabled={!without.length}
                    title={without.length ? 'Recommend a team member without naming an amount' : 'Everyone in your team is on payroll or already recommended'}>
              <Icon name="team" />Recommend for payroll
            </button>
          )}
          <button className="btn primary" onClick={() => setEditing('new')} disabled={!without.length}
                  title={without.length ? '' : 'Everyone has a salary or a proposal — use the row buttons'}>
            <Icon name="plus" />{isDirector ? 'Set salary' : 'Propose salary'}
          </button>
        </div>
      </div>
      {without.length > 0 && (
        <div className="inline pay-note">{without.length} {isDirector ? 'active people have' : 'of your reportees have'} no salary yet and won't be in payroll: {without.slice(0, 6).map((p) => p.name || p.email).join(', ')}{without.length > 6 ? '…' : ''}</div>
      )}
      {!isDirector && <small className="dim pay-foot">Recommend someone without an amount, or propose a salary yourself — either way a director decides.</small>}
      {!current.length && !pending.length ? <Empty title="No salaries yet" hint={isDirector ? "Set each person's monthly salary to include them in payroll." : 'Propose a salary for someone in your team; a director approves it.'} /> : (
        <div className="table-wrap">
          <table className="leave-table">
            <thead><tr><th>Person</th><th>Basic</th><th>HRA</th><th>Other</th><th>Gross / month</th><th>Since</th><th /></tr></thead>
            <tbody>
              {[...current, ...pending.filter((p) => !current.some((c) => c.accountId === p.accountId))].map((r) => {
                const cur = r.status === 'approved' ? r : null;
                const wait = pendingFor(r.accountId);
                const rej = latestRejected(r.accountId);
                const changes = rows.filter((x) => x.accountId === r.accountId).length;
                return (
                  <tr key={r.accountId}>
                    <td>
                      <div className="who"><Avatar label={r.name} /><span>{r.name}<small>{r.email}</small></span></div>
                      {wait && <small className="sub pay-flag">proposed {money(wait.gross)} from {wait.effectiveFrom} — waiting for a director</small>}
                      {!wait && rej && <small className="sub pay-rejected" title={rej.decisionNote}>last proposal rejected: “{rej.decisionNote}”</small>}
                    </td>
                    <td className="mono">{cur ? money(cur.basic) : '—'}</td>
                    <td className="mono">{cur ? money(cur.hra) : '—'}</td>
                    <td className="mono">{cur ? money(cur.otherAllowance) : '—'}</td>
                    <td className="mono strong">{cur ? money(cur.gross) : <span className="pill warn">pending</span>}</td>
                    <td className="mono dim">{cur ? cur.effectiveFrom : '—'}</td>
                    <td className="row-act"><div className="leave-act">
                      {changes > 1 && <button className="btn ghost small" onClick={() => setHistory(r.accountId)}>History ({changes})</button>}
                      {cur && (isDirector || !wait) && (
                        <button className="btn ghost small" onClick={() => setEditing(cur)}><Icon name="edit" size={14} />{isDirector ? 'Change' : 'Propose change'}</button>
                      )}
                    </div></td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>

    {!isDirector && recs.length > 0 && (
      <section className="panel">
        <div className="panel-head"><h3>Your recommendations</h3><span className="muted">{recsPending.length} waiting</span></div>
        <ul className="perf-list">
          {recs.slice(0, 20).map((r) => (
            <li key={r.id}>
              <span><b>{r.name}</b>
                <small>recommended {fmtStamp(r.createdAt)}{r.startDate ? ` · start ${r.startDate}` : ''}{r.note ? ` · “${r.note}”` : ''}</small>
                {r.status !== 'pending' && <small className={r.status === 'declined' ? 'pay-rejected' : ''}>
                  {r.status} by {r.decidedBy || '—'} {fmtStamp(r.decidedAt)}{r.decisionNote ? ` — “${r.decisionNote}”` : ''}</small>}
              </span>
              <span className={`pill ${REC_PILL[r.status]}`}>{REC_LABEL[r.status]}</span>
            </li>
          ))}
        </ul>
      </section>
    )}

    {recommending && <RecommendDialog me={me} people={without} onClose={() => setRecommending(false)} onDone={done} />}
    {declining && <DeclineDialog me={me} rec={declining} onClose={() => setDeclining(null)} onDone={done} />}
    {accepting && <SalaryDialog me={me} people={[]}
                                preset={{ accountId: accepting.accountId, name: accepting.name, effectiveFrom: accepting.startDate,
                                          by: accepting.recommendedBy, note: accepting.note }}
                                onClose={() => setAccepting(null)} onDone={done} />}
    {editing && <SalaryDialog me={me} people={without} person={editing === 'new' ? null : editing}
                              onClose={() => setEditing(null)} onDone={done} />}
    {rejecting && <RejectDialog me={me} row={rejecting} onClose={() => setRejecting(null)} onDone={done} />}
    {history && (
      <Modal title={rows.find((r) => r.accountId === history).name} eyebrow="Salary history" onClose={() => setHistory(null)}>
        <ul className="perf-list">
          {rows.filter((r) => r.accountId === history).map((r) => (
            <li key={r.id}>
              <span><b>{money(r.gross)} / month</b>
                <small>from {r.effectiveFrom} · basic {money(r.basic)} · HRA {money(r.hra)} · other {money(r.otherAllowance)}</small>
                <small>{r.status === 'approved' && r.createdBy === r.decidedBy ? `set by ${r.createdBy || '—'}` : `proposed by ${r.createdBy || '—'}`} {fmtStamp(r.createdAt)}
                  {r.decidedBy && r.createdBy !== r.decidedBy ? ` · ${r.status} by ${r.decidedBy} ${fmtStamp(r.decidedAt)}` : ''}
                  {r.decisionNote ? ` · “${r.decisionNote}”` : ''}</small>
              </span>
              <span className={`pill ${SAL_STATUS[r.status]}`}>{SAL_LABEL[r.status]}</span>
            </li>
          ))}
        </ul>
      </Modal>
    )}
  </>);
}

/* --------------------------------------------------------- manager team */

/**
 * Manager's "My team": every reportee with their payroll status. Managers
 * approve pay for their own team - setting a salary applies at once - and
 * decide requests for their reportees (e.g. a co-manager's recommendation).
 */
function TeamPanel({ me, people, flash, setError }) {
  const [rows, setRows] = useState([]);
  const [recs, setRecs] = useState([]);
  const [loaded, setLoaded] = useState(false);
  const [recommend, setRecommend] = useState(null);  // null | 'pick' | accountId
  const [setting, setSetting] = useState(null);      // null | {pick} | {person} | {preset}
  const [declining, setDeclining] = useState(null);
  const [rejecting, setRejecting] = useState(null);
  const [busy, setBusy] = useState(null);
  const load = useCallback(async () => {
    try {
      const [sal, rec] = await Promise.all([
        call('GET', '/api/payroll/salaries', null, me.token),
        call('GET', '/api/payroll/recommendations', null, me.token),
      ]);
      setRows(sal.salaries || []);
      setRecs(rec.recommendations || []);
    } catch (e) { setError(e.message); }
    finally { setLoaded(true); }
  }, [me.token, setError]);
  useEffect(() => { load(); }, [load]);

  // one row per reportee (the team list has a row per project line), never yourself
  const team = useMemo(() => {
    const map = new Map();
    for (const p of people) {
      if (p.id === me.id || (p.status ?? 'active') !== 'active') continue;
      const had = map.get(p.id);
      map.set(p.id, had ? { ...had, projects: [...had.projects, p.project].filter(Boolean) } : { ...p, projects: [p.project].filter(Boolean) });
    }
    return [...map.values()].sort((a, b) => (a.name || a.email).localeCompare(b.name || b.email));
  }, [people, me.id]);

  // payroll state per person: rows/recs come newest first
  const stateOf = (id) => {
    const salary = rows.find((r) => r.accountId === id && r.status === 'approved');
    const proposal = rows.find((r) => r.accountId === id && r.status === 'pending');
    const rec = recs.find((r) => r.accountId === id && r.status === 'pending');
    const lastNo = [...rows.filter((r) => r.accountId === id && r.status === 'rejected'),
      ...recs.filter((r) => r.accountId === id && r.status === 'declined')]
      .sort((a, b) => (b.decidedAt || 0) - (a.decidedAt || 0))[0];
    return { salary, proposal, rec, lastNo };
  };
  const states = team.map((p) => ({ p, ...stateOf(p.id) }));
  const notOnPayroll = states.filter((x) => !x.salary).map((x) => x.p);
  const recommendable = states.filter((x) => !x.salary && !x.proposal && !x.rec).map((x) => x.p);
  const onPayroll = states.filter((x) => x.salary).length;
  const openRecs = states.filter((x) => x.rec).map((x) => x.rec);
  const openProposals = states.filter((x) => x.proposal).map((x) => x.proposal);
  const done = (m) => {
    setRecommend(null); setSetting(null); setDeclining(null); setRejecting(null);
    flash(m); load();
  };
  const approve = async (r) => {
    setBusy(r.id);
    try { await call('POST', `/api/payroll/salaries/${r.id}/approve`, {}, me.token); done(`Approved ${r.name}'s salary`); }
    catch (e) { setError(e.message); }
    finally { setBusy(null); }
  };
  const mine = recs.filter((r) => team.some((p) => p.id === r.accountId));

  if (!loaded) return <p className="muted">Loading…</p>;
  return (<>
    <div className="stats">
      <Stat label="Reportees" value={team.length} icon="team" foot="people who report to you" />
      <Stat label="On payroll" value={onPayroll} accent="cyan" icon="money" foot="salary set" />
      <Stat label="Waiting for you" value={openRecs.length + openProposals.length} accent={openRecs.length + openProposals.length ? 'saffron' : ''}
            icon="clock" foot="recommendations to decide" />
      <Stat label="Not on payroll" value={notOnPayroll.length} accent={notOnPayroll.length ? 'alert' : ''} icon="user"
            foot={notOnPayroll.length ? 'set their salary below' : 'everyone is covered'} />
    </div>

    {(openRecs.length > 0 || openProposals.length > 0) && (
      <section className="panel">
        <div className="panel-head"><h3>Waiting for you</h3><span className="muted">{openRecs.length + openProposals.length} to decide</span></div>
        <div className="leave-cards">
          {openRecs.map((r) => (
            <div key={`rec${r.id}`} className="leave-card">
              <div className="who"><Avatar label={r.name} /><span>{r.name}<small>recommended by {r.recommendedBy === (me.name || me.email) ? 'you' : r.recommendedBy} · {fmtStamp(r.createdAt)}</small></span></div>
              <b>{r.startDate ? `Start ${fmtDay(r.startDate)} ${r.startDate.slice(0, 4)}` : 'No start date given'}</b>
              {r.note ? <p title={r.note}>{r.note}</p> : <p className="dim">No note.</p>}
              <div className="leave-act">
                <button className="btn danger small" onClick={() => setDeclining(r)}>Decline</button>
                <button className="btn primary small" onClick={() => setSetting({ preset: { accountId: r.accountId, name: r.name, effectiveFrom: r.startDate, by: r.recommendedBy, note: r.note } })}>
                  <Icon name="approve" size={15} />Approve
                </button>
              </div>
            </div>
          ))}
          {openProposals.map((r) => {
            const now = rows.find((x) => x.accountId === r.accountId && x.status === 'approved');
            return (
              <div key={`sal${r.id}`} className="leave-card">
                <div className="who"><Avatar label={r.name} /><span>{r.name}<small>proposed by {r.createdBy} · {fmtStamp(r.createdAt)}</small></span></div>
                <div className="pay-propose">
                  <span><small>Now</small><b>{now ? money(now.gross) : 'no salary'}</b></span>
                  <span>→</span>
                  <span><small>Proposed from {r.effectiveFrom}</small><b>{money(r.gross)}</b></span>
                </div>
                <div className="leave-act">
                  <button className="btn danger small" onClick={() => setRejecting(r)}>Reject</button>
                  <button className="btn primary small" disabled={busy === r.id} onClick={() => approve(r)}><Icon name="approve" size={15} />Approve</button>
                </div>
              </div>
            );
          })}
        </div>
      </section>
    )}

    <section className="panel">
      <div className="panel-head">
        <h3>My team</h3>
        <div className="leave-act">
          <button className="btn ghost" onClick={() => setRecommend('pick')} disabled={!recommendable.length}
                  title={recommendable.length ? 'Leave the amount to a director or a co-manager' : 'Everyone is on payroll or already waiting'}>
            <Icon name="team" />Recommend instead
          </button>
          <button className="btn primary" onClick={() => setSetting({ pick: true })} disabled={!notOnPayroll.length}
                  title={notOnPayroll.length ? 'Pick a reportee and set their salary' : 'Everyone in your team is on payroll'}>
            <Icon name="plus" />Add reportee to payroll
          </button>
        </div>
      </div>
      {!team.length ? (
        <Empty title="No one reports to you yet" hint="Ask a director to set you as someone's reporting manager, or add an employee from My team." />
      ) : (
        <div className="table-wrap">
          <table className="leave-table">
            <thead><tr><th>Reportee</th><th>Device</th><th>Payroll</th><th>Salary / month</th><th /></tr></thead>
            <tbody>
              {states.map(({ p, salary, proposal, rec, lastNo }) => (
                <tr key={p.id}>
                  <td><div className="who"><Avatar label={p.name || p.email} /><span>{p.name || p.email}<small>{p.email}{p.projects.length ? ` · ${p.projects.join(', ')}` : ''}</small></span></div></td>
                  <td>{p.enrollNumber != null ? <span className="mono dim">#{p.enrollNumber}</span> : <span className="pay-flag">not linked</span>}</td>
                  <td>
                    {rec ? <span className="pill warn">Recommended — waiting</span>
                      : proposal ? <span className="pill warn">Salary proposed — waiting</span>
                      : salary ? <span className="pill live">On payroll</span>
                      : <span className="pill idle">Not on payroll</span>}
                    {rec && <small className="sub">by {rec.recommendedBy} · {fmtStamp(rec.createdAt)}</small>}
                    {proposal && <small className="sub">{money(proposal.gross)} from {proposal.effectiveFrom}</small>}
                    {salary && !rec && !proposal && <small className="sub">set by {salary.decidedBy || salary.createdBy || '—'}</small>}
                    {!rec && !proposal && !salary && lastNo && (
                      <small className="sub pay-rejected" title={lastNo.decisionNote}>
                        {lastNo.status === 'declined' ? 'recommendation declined' : 'proposal rejected'}: “{lastNo.decisionNote}”</small>
                    )}
                  </td>
                  <td className="mono">{salary ? <>{money(salary.gross)}<small className="sub">since {salary.effectiveFrom}</small></> : <span className="dim">—</span>}</td>
                  <td className="row-act"><div className="leave-act">
                    {!salary && !proposal && !rec && (
                      <button className="btn ghost small" onClick={() => setRecommend(p.id)} title="Leave the amount to a director or co-manager">Recommend</button>
                    )}
                    {!salary && (
                      <button className="btn primary small" onClick={() => setSetting({ preset: { accountId: p.id, name: p.name || p.email, by: 'you' } })}>
                        <Icon name="plus" size={14} />Set salary
                      </button>
                    )}
                    {salary && (
                      <button className="btn ghost small" onClick={() => setSetting({ person: salary })}><Icon name="edit" size={14} />Change</button>
                    )}
                  </div></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <small className="dim pay-foot">You approve pay for your own team: a salary you set applies straight away (it also closes any
        recommendation for that person). <b>Recommend</b> leaves the amount to a director or another of their managers.
        Directors can still see and change everything.</small>
    </section>

    {mine.length > 0 && (
      <section className="panel">
        <div className="panel-head"><h3>Recommendations</h3><span className="muted">{mine.filter((r) => r.status === 'pending').length} waiting</span></div>
        <ul className="perf-list">
          {mine.slice(0, 20).map((r) => (
            <li key={r.id}>
              <span><b>{r.name}</b>
                <small>recommended by {r.recommendedBy || '—'} {fmtStamp(r.createdAt)}{r.startDate ? ` · start ${r.startDate}` : ''}{r.note ? ` · “${r.note}”` : ''}</small>
                {r.status !== 'pending' && <small className={r.status === 'declined' ? 'pay-rejected' : ''}>
                  {r.status} by {r.decidedBy || '—'} {fmtStamp(r.decidedAt)}{r.decisionNote ? ` — “${r.decisionNote}”` : ''}</small>}
              </span>
              <span className={`pill ${REC_PILL[r.status]}`}>{REC_LABEL[r.status]}</span>
            </li>
          ))}
        </ul>
      </section>
    )}

    {recommend && <RecommendDialog me={me} people={recommendable} preselect={recommend === 'pick' ? null : recommend}
                                   onClose={() => setRecommend(null)} onDone={done} />}
    {setting && <SalaryDialog me={me} people={setting.pick ? notOnPayroll : []} person={setting.person || null}
                              preset={setting.preset || null} onClose={() => setSetting(null)} onDone={done} />}
    {declining && <DeclineDialog me={me} rec={declining} onClose={() => setDeclining(null)} onDone={done} />}
    {rejecting && <RejectDialog me={me} row={rejecting} onClose={() => setRejecting(null)} onDone={done} />}
  </>);
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
  const [section, setSection] = useState(isDirector ? 'run' : 'team');
  const [notice, setNotice] = useState(null);
  const [error, setError] = useState(null);
  const [waiting, setWaiting] = useState(0); // salary proposals waiting for a director
  const [otWaiting, setOtWaiting] = useState(0); // overtime claims waiting on you
  const [person, setPerson] = useState(null); // { accountId, name } for the history dialog
  const flash = useCallback((m) => { setNotice(m); setTimeout(() => setNotice(null), 5000); }, []);
  useEffect(() => {
    if (!isDirector) return;
    Promise.all([call('GET', '/api/payroll/salaries', null, me.token), call('GET', '/api/payroll/recommendations', null, me.token)])
      .then(([a, b]) => setWaiting([...(a.salaries || []), ...(b.recommendations || [])].filter((r) => r.status === 'pending').length))
      .catch(() => {});
  }, [isDirector, me.token]);
  useEffect(() => {
    call('GET', '/api/overtime/team', null, me.token).then((d) => setOtWaiting(pendingOvertimeCount(d.claims || [], me))).catch(() => {});
  }, [me]);
  const sections = isDirector
    ? [['run', 'Monthly payroll'], ['overtime', 'Overtime'], ['salaries', 'Salaries'], ['holidays', 'Holidays']]
    : [['team', 'My team'], ['run', 'Monthly payroll'], ['overtime', 'Overtime']];

  return (<>
    <Toasts notice={notice} error={error} onClose={() => setError(null)} />
    <div className="role-picks pay-sections">
      {sections.map(([k, label]) => (
        <button key={k} className={`role-pick ${section === k ? 'on' : ''}`} onClick={() => setSection(k)}>
          {label}{k === 'salaries' && waiting > 0 && <em className="nav-count">{waiting}</em>}
          {k === 'overtime' && otWaiting > 0 && <em className="nav-count">{otWaiting}</em>}
        </button>
      ))}
    </div>
    {section === 'run' && <RunPanel me={me} isDirector={isDirector} flash={flash} setError={setError} onPerson={setPerson} />}
    {section === 'overtime' && <OvertimePanel me={me} flash={flash} setError={setError} onCount={setOtWaiting} onPerson={setPerson} />}
    {isDirector && section === 'salaries' && <SalariesPanel me={me} isDirector people={people} flash={flash}
                                                            setError={setError} onPending={setWaiting} />}
    {!isDirector && section === 'team' && <TeamPanel me={me} people={people} flash={flash} setError={setError} />}
    {isDirector && section === 'holidays' && <HolidaysPanel me={me} flash={flash} setError={setError} />}
    {!isDirector && section === 'run' && <p className="dim pay-foot">You see your reportees' pay once a director finalizes the month.</p>}
    {person && <PersonPayDialog me={me} person={person} onClose={() => setPerson(null)} />}
  </>);
}

/**
 * One person's payroll months and overtime. Directors see every month; managers their reportees'
 * finalized months (the server scopes both).
 */
function PersonPayDialog({ me, person, onClose }) {
  const [lines, setLines] = useState(null);
  const [claims, setClaims] = useState(null);
  const [days, setDays] = useState(null);
  const [error, setError] = useState(null);
  useEffect(() => {
    call('GET', `/api/payroll/person/${person.accountId}`, null, me.token)
      .then((d) => setLines(d.lines || [])).catch((e) => { setLines([]); setError(e.message); });
    call('GET', `/api/overtime/person/${person.accountId}`, null, me.token)
      .then((d) => setClaims(d.claims || [])).catch((e) => { setClaims([]); setError(e.message); });
  }, [me.token, person.accountId]);
  if (days) return <DaysDialog me={me} line={days} onClose={() => setDays(null)} />;

  return (
    <Modal title={person.name} eyebrow="Pay & overtime history" onClose={onClose}>
      {error && <div className="inline err">{error}</div>}
      <h4 className="pay-sub">Payroll months</h4>
      {!lines ? <p className="muted">Loading…</p> : !lines.length ? <p className="dim">No payroll months to show.</p> : (
        <div className="table-wrap pay-days">
          <table className="leave-table">
            <thead><tr><th>Month</th><th>Status</th><th>Days</th><th>Missed</th><th>Cut</th><th>Overtime</th><th>Net</th><th /></tr></thead>
            <tbody>
              {lines.map((l) => (
                <tr key={l.month}>
                  <td className="mono">{monthLabel(l.month)}</td>
                  <td><StatusPill status={l.status} /></td>
                  <td className="mono">{l.workingDays}</td>
                  <td className="mono">{hrs(totalHours(l))}</td>
                  <td className="mono">{l.cut ? `− ${money(l.cut)}` : '—'}</td>
                  <td className="mono">{l.overtimePay ? <>+ {money(l.overtimePay)}<small className="sub">{otDaysLabel(l.overtimeDays)}</small></> : '—'}</td>
                  <td className="mono strong">{money(l.net)}</td>
                  <td className="row-act"><button className="btn ghost small" onClick={() => setDays(l)}>Days</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <h4 className="pay-sub">Overtime</h4>
      {!claims ? <p className="muted">Loading…</p> : <OvertimeHistory claims={claims} />}
    </Modal>
  );
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
            {p.overtimePay > 0 && <><dt>Overtime · {otDaysLabel(p.overtimeDays)} × {money(p.dayRate)}</dt>
              <dd>+ {money(p.overtimePay)}</dd></>}
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
        Sundays, holidays and approved leave are paid. {OT_RULE}</small>
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
    <MyOvertime me={me} />
    {!list ? <p className="muted">Loading…</p> : !list.length ? (
      <section className="panel"><Empty title="No payslips yet" hint="A payslip appears here once a director finalizes the month." /></section>
    ) : (<>
      <div className="stats">
        <Stat label="Latest net pay" value={money(list[0].net)} accent="cyan" icon="money" foot={monthLabel(list[0].month)} />
        <Stat label="Latest cut" value={money(list[0].cut)} accent={list[0].cut ? 'saffron' : ''} icon="clock"
              foot={`${hrs(totalHours(list[0]))} missed`} />
        <Stat label="Latest overtime" value={money(list[0].overtimePay)} icon="bolt" foot={`${otDaysLabel(list[0].overtimeDays)} approved`} />
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
