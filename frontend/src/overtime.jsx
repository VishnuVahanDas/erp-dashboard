/*
 * Overtime — time past a 9:00 h span (first-to-last punch, lunch included), paid in quarter days once a
 * director or the person's manager approves it. The employee claims a past day from their punches, or an
 * approver approves a day straight away. Payroll pays approved days at the day rate.
 */

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Avatar, Empty, Icon, Modal, Toasts, call, fmtDuration } from './ui.jsx';

const PILL = { pending: 'warn', approved: 'live', rejected: 'bad', cancelled: 'idle' };
const STATUS = { pending: 'pending', approved: 'approved', rejected: 'declined', cancelled: 'withdrawn' };
export const OT_RULE = 'Overtime counts whole hours past the 9 h day (first to last punch, 1 h lunch included; 8:30 h is a full day). '
  + 'Span 10:00–11:59 = ¼ day, 12:00–13:59 = ½ day, 14:00–15:59 = ¾ day, 16:00 or more = 1 day (the most for one day). '
  + 'Under 10:00 there’s no overtime. Approved days are paid at the day rate.';

const fmtDay = (s) => new Date(`${s}T00:00:00Z`).toLocaleDateString('en-GB', { weekday: 'short', day: '2-digit', month: 'short', year: 'numeric', timeZone: 'UTC' });
const fmtStamp = (ms) => (ms ? new Date(ms).toLocaleString('en-GB', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' }) : '—');
export const otDaysLabel = (d) => `${Math.round(d * 100) / 100} day${d > 1 ? 's' : ''}`;
const worked = (x) => `${x.first}–${x.last} · ${fmtDuration(x.spanMin)} (${fmtDuration(x.otMin)} over)`;

/** Claim (employee), approve / decline a claim, or approve a day without a claim (approver). */
function OtDialog({ me, mode, item, onClose, onDone }) {
  const [text, setText] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const first = item.name?.split(' ')[0] || 'them';
  const cfg = {
    claim: { title: 'Claim overtime', eyebrow: 'Overtime', label: 'What did you work on? (optional)', ok: 'Send claim',
             required: false, placeholder: 'e.g. Finished the drone delivery for the client.' },
    approve: { title: 'Approve overtime', eyebrow: item.name, label: `Note for ${first} (optional)`, ok: 'Approve',
               required: false, placeholder: 'e.g. Thanks for staying for the launch.' },
    grant: { title: 'Approve overtime', eyebrow: item.name, label: `Note for ${first} (optional)`, ok: 'Approve',
             required: false, placeholder: 'e.g. Stayed back for the site test.' },
    reject: { title: 'Decline overtime', eyebrow: item.name, label: `Reason (shown to ${first})`, ok: 'Decline',
              required: true, placeholder: 'e.g. This wasn’t agreed in advance.' },
  }[mode];

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError(null);
    try {
      if (mode === 'claim') {
        await call('POST', '/api/overtime', { day: item.day, reason: text }, me.token);
        onDone('Sent to your reporting manager');
      } else if (mode === 'grant') {
        await call('POST', '/api/overtime/grant', { accountId: item.accountId, day: item.day, note: text }, me.token);
        onDone(`Approved ${item.name}'s overtime`);
      } else {
        await call('POST', `/api/overtime/${item.id}/${mode}`, { note: text }, me.token);
        onDone(`${mode === 'approve' ? 'Approved' : 'Declined'} ${item.name}'s overtime`);
      }
    } catch (err) { setError(err.message); setBusy(false); }
  };

  return (
    <Modal title={cfg.title} eyebrow={cfg.eyebrow} onClose={onClose}>
      <form className="person-form" onSubmit={submit}>
        <div className="leave-sum"><Icon name="clock" /><span>
          <b>{fmtDay(item.day)}</b> · {worked(item)} → <b>{otDaysLabel(item.days)}</b> of overtime
        </span></div>
        {item.reason && <p className="muted">“{item.reason}”</p>}
        <label className="field"><span>{cfg.label}</span>
          <textarea rows={2} maxLength={500} required={cfg.required} value={text} autoFocus
                    onChange={(e) => setText(e.target.value)} placeholder={cfg.placeholder} />
        </label>
        <small className="dim">{OT_RULE}{mode !== 'claim' && ' The punches are checked again when you approve.'}</small>
        {error && <div className="inline err">{error}</div>}
        <div className="modal-actions">
          <button type="button" className="btn ghost" onClick={onClose}>Cancel</button>
          <button className={`btn ${mode === 'reject' ? 'danger' : 'primary'}`} disabled={busy || (cfg.required && !text.trim())}>
            {mode !== 'reject' && <Icon name="approve" size={15} />}{busy ? 'Saving…' : cfg.ok}
          </button>
        </div>
      </form>
    </Modal>
  );
}

/** One claim as a list row: day, punches, days, status and who decided. */
function ClaimRow({ c, showName, onWithdraw }) {
  return (
    <li>
      <span>
        <b>{showName ? `${c.name} · ` : ''}{fmtDay(c.day)} · {otDaysLabel(c.days)}</b>
        <small>{worked(c)}{c.reason ? ` — “${c.reason}”` : ''}</small>
        {c.status !== 'pending' && c.status !== 'cancelled' && (
          <small className={c.status === 'rejected' ? 'pay-rejected' : ''}>
            {c.selfRequested ? '' : 'approved without a claim · '}{STATUS[c.status]} by {c.decidedBy || '—'} {fmtStamp(c.decidedAt)}
            {c.decisionNote ? ` — “${c.decisionNote}”` : ''}</small>
        )}
      </span>
      <span className="leave-act">
        <span className={`pill ${PILL[c.status]}`}>{STATUS[c.status]}</span>
        {onWithdraw && c.status === 'pending' && <button className="btn ghost small" onClick={() => onWithdraw(c)}>Withdraw</button>}
      </span>
    </li>
  );
}

/** A person's overtime claims, newest first (person dialog in payroll). */
export function OvertimeHistory({ claims }) {
  if (!claims.length) return <p className="dim">No overtime yet.</p>;
  return <ul className="perf-list">{claims.map((c) => <ClaimRow key={c.id} c={c} />)}</ul>;
}

/** Employee: days whose punches show overtime, claims and their status. */
export function MyOvertime({ me }) {
  const [data, setData] = useState(null);
  const [claiming, setClaiming] = useState(null);
  const [notice, setNotice] = useState(null);
  const [error, setError] = useState(null);
  const load = useCallback(async () => {
    try { setData(await call('GET', '/api/overtime/my', null, me.token)); }
    catch (e) { setError(e.message); setData({ claims: [], available: [] }); }
  }, [me.token]);
  useEffect(() => { load(); }, [load]);
  const flash = (m) => { setNotice(m); setTimeout(() => setNotice(null), 5000); };
  const withdraw = async (c) => {
    if (!window.confirm(`Withdraw your overtime claim for ${fmtDay(c.day)}?`)) return;
    try { await call('POST', `/api/overtime/${c.id}/cancel`, {}, me.token); flash('Claim withdrawn'); load(); }
    catch (e) { setError(e.message); }
  };
  const approvedDays = (data?.claims || []).filter((c) => c.status === 'approved').reduce((s, c) => s + c.days, 0);

  return (<>
    <Toasts notice={notice} error={error} onClose={() => setError(null)} />
    <section className="panel">
      <div className="panel-head">
        <h3>Overtime</h3>
        {data && <span className="muted">{otDaysLabel(approvedDays)} approved · paid in that month’s payslip</span>}
      </div>
      {!data ? <p className="muted">Loading…</p> : (<>
        {data.available.length > 0 && (
          <div className="missing-box ot-box">
            <b><Icon name="clock" size={15} />You worked overtime on {data.available.length} day{data.available.length === 1 ? '' : 's'}</b>
            <small className="dim">Claim it and your reporting manager approves it.</small>
            <ul className="perf-list">
              {data.available.map((a) => (
                <li key={a.day}>
                  <span><b>{fmtDay(a.day)} · {otDaysLabel(a.days)}</b><small>{worked(a)}</small></span>
                  <button className="btn primary small" onClick={() => setClaiming(a)}>Claim</button>
                </li>
              ))}
            </ul>
          </div>
        )}
        {!data.claims.length
          ? <p className="dim">{OT_RULE}</p>
          : <ul className="perf-list">{data.claims.slice(0, 15).map((c) => <ClaimRow key={c.id} c={c} onWithdraw={withdraw} />)}</ul>}
      </>)}
    </section>
    {claiming && <OtDialog me={me} mode="claim" item={claiming} onClose={() => setClaiming(null)}
                           onDone={(m) => { setClaiming(null); flash(m); load(); }} />}
  </>);
}

/** Pending claims this approver can decide (never their own). */
export const pendingOvertimeCount = (claims, me) => claims.filter((c) => c.status === 'pending' && c.accountId !== me.id).length;

/**
 * Payroll → Overtime for directors (everyone) and managers (their reportees): claims to decide,
 * overtime found in punches that nobody claimed, and the history by month and person.
 */
export function OvertimePanel({ me, flash, setError, onCount, onPerson }) {
  const [data, setData] = useState(null);
  const [acting, setActing] = useState(null); // { mode, item }
  const [month, setMonth] = useState('');
  const [person, setPerson] = useState('');
  const load = useCallback(async () => {
    try {
      const d = await call('GET', '/api/overtime/team', null, me.token);
      setData(d);
      onCount?.(pendingOvertimeCount(d.claims || [], me));
    } catch (e) { setError(e.message); setData({ claims: [], available: [] }); }
  }, [me, onCount, setError]);
  useEffect(() => { load(); }, [load]);

  const claims = useMemo(() => data?.claims || [], [data]);
  const pending = claims.filter((c) => c.status === 'pending' && c.accountId !== me.id).sort((a, b) => a.day.localeCompare(b.day));
  const people = useMemo(() => [...new Map(claims.map((c) => [c.accountId, c.name])).entries()]
    .sort((a, b) => a[1].localeCompare(b[1])), [claims]);
  const history = claims.filter((c) => c.status !== 'pending' && c.status !== 'cancelled'
    && (!month || c.day.startsWith(month)) && (!person || String(c.accountId) === person));
  const approvedDays = history.filter((c) => c.status === 'approved').reduce((s, c) => s + c.days, 0);

  if (!data) return <p className="muted">Loading…</p>;
  return (<>
    {pending.length > 0 && (
      <section className="panel">
        <div className="panel-head"><h3>Overtime claims waiting</h3><span className="muted">{pending.length} to decide · paid once approved</span></div>
        <div className="leave-cards">
          {pending.map((c) => (
            <div key={c.id} className="leave-card">
              <div className="who"><Avatar label={c.name} /><span>{c.name}<small>claimed {fmtStamp(c.createdAt)}</small></span></div>
              <div className="leave-what"><b>{fmtDay(c.day)} · {otDaysLabel(c.days)}</b><span className="muted">{worked(c)}</span></div>
              {c.reason && <p title={c.reason}>{c.reason}</p>}
              <div className="leave-act">
                <button className="btn danger small" onClick={() => setActing({ mode: 'reject', item: c })}>Decline</button>
                <button className="btn primary small" onClick={() => setActing({ mode: 'approve', item: c })}><Icon name="approve" size={15} />Approve</button>
              </div>
            </div>
          ))}
        </div>
      </section>
    )}

    <section className="panel">
      <div className="panel-head"><h3>Overtime found in punches</h3><span className="muted">not claimed yet · last 2 months</span></div>
      {!data.available.length ? <p className="dim">No unclaimed overtime. {OT_RULE}</p> : (
        <div className="table-wrap">
          <table className="leave-table">
            <thead><tr><th>Person</th><th>Day</th><th>In–out</th><th>Worked</th><th>Over 9:00 h</th><th>Pays</th><th /></tr></thead>
            <tbody>
              {data.available.map((a) => (
                <tr key={`${a.accountId}-${a.day}`}>
                  <td><button className="link who" onClick={() => onPerson?.(a)}><Avatar label={a.name} /><span>{a.name}<small>{a.email}</small></span></button></td>
                  <td className="mono">{fmtDay(a.day)}</td>
                  <td className="mono">{a.first}–{a.last}</td>
                  <td className="mono">{fmtDuration(a.spanMin)}</td>
                  <td className="mono">{fmtDuration(a.otMin)}</td>
                  <td className="mono strong">{otDaysLabel(a.days)}</td>
                  <td className="row-act"><button className="btn primary small" onClick={() => setActing({ mode: 'grant', item: a })}>
                    <Icon name="approve" size={14} />Approve</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>

    <section className="panel">
      <div className="toolbar">
        <h3 style={{ margin: 0 }}>Overtime history</h3>
        <span className="push" />
        <input type="month" className="date" value={month} onChange={(e) => setMonth(e.target.value)} style={{ width: 'auto' }} title="Month" />
        <select className="date" value={person} onChange={(e) => setPerson(e.target.value)} style={{ width: 'auto' }}>
          <option value="">Everyone</option>
          {people.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
        </select>
        {(month || person) && <button className="btn ghost small" onClick={() => { setMonth(''); setPerson(''); }}>Clear</button>}
      </div>
      {!history.length ? <Empty title="No decided overtime" hint="Approved and declined overtime shows here." /> : (<>
        <p className="muted">{otDaysLabel(approvedDays)} approved in this view.</p>
        <div className="table-wrap">
          <table className="leave-table">
            <thead><tr><th>Person</th><th>Day</th><th>Worked</th><th>Pays</th><th>Status</th><th>Decided</th></tr></thead>
            <tbody>
              {history.map((c) => (
                <tr key={c.id}>
                  <td><button className="link who" onClick={() => onPerson?.(c)}><Avatar label={c.name} /><span>{c.name}<small>{c.email}</small></span></button></td>
                  <td className="mono">{fmtDay(c.day)}</td>
                  <td className="mono">{worked(c)}</td>
                  <td className="mono strong">{otDaysLabel(c.days)}</td>
                  <td><span className={`pill ${PILL[c.status]}`}>{STATUS[c.status]}</span>{!c.selfRequested && <small className="sub">without a claim</small>}</td>
                  <td>{c.decidedBy || '—'}<small className="sub">{fmtStamp(c.decidedAt)}</small>
                    {c.decisionNote && <small className="sub reason" title={c.decisionNote}>“{c.decisionNote}”</small>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </>)}
    </section>

    {acting && <OtDialog me={me} mode={acting.mode} item={acting.item} onClose={() => setActing(null)}
                         onDone={(m) => { setActing(null); flash(m); load(); }} />}
  </>);
}
