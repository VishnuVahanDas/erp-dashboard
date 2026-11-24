/* Missed-punch requests — employees ask, their manager (or a director) approves; payroll uses approved fixes. */

import { useCallback, useEffect, useState } from 'react';
import { Avatar, Icon, Modal, Toasts, call, fmtDuration } from './ui.jsx';

const PILL = { pending: 'warn', approved: 'live', rejected: 'bad', cancelled: 'idle' };
const fmtDay = (s) => new Date(`${s}T00:00:00Z`).toLocaleDateString('en-GB', { weekday: 'short', day: '2-digit', month: 'short', year: 'numeric', timeZone: 'UTC' });
const fmtStamp = (ms) => (ms ? new Date(ms).toLocaleString('en-GB', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' }) : '—');
const span = (i, o) => {
  const [a, b] = [i, o].map((t) => +t.slice(0, 2) * 60 + +t.slice(3, 5));
  return Math.max(0, b - a);
};
const yesterday = () => { const d = new Date(); d.setDate(d.getDate() - 1); return d.toLocaleDateString('sv'); };

function FixDialog({ me, onClose, onDone }) {
  const [day, setDay] = useState(yesterday());
  const [inT, setIn] = useState('09:00');
  const [outT, setOut] = useState('18:00');
  const [reason, setReason] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const mins = inT && outT ? span(inT, outT) : 0;

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError(null);
    try {
      await call('POST', '/api/punch-fixes', { day, in: inT, out: outT, reason }, me.token);
      onDone('Sent to your manager');
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  };

  return (
    <Modal title="Fix a missed punch" eyebrow="Attendance" onClose={onClose}>
      <form className="person-form" onSubmit={submit}>
        <label className="field"><span>Day</span>
          <input type="date" required max={yesterday()} value={day} onChange={(e) => setDay(e.target.value)} />
        </label>
        <div className="form-grid">
          <label className="field"><span>In</span><input type="time" required value={inT} onChange={(e) => setIn(e.target.value)} /></label>
          <label className="field"><span>Out</span><input type="time" required value={outT} onChange={(e) => setOut(e.target.value)} /></label>
        </div>
        <label className="field"><span>What happened?</span>
          <textarea rows={3} maxLength={500} required value={reason} onChange={(e) => setReason(e.target.value)}
                    placeholder="e.g. Forgot to punch out — left at 6 pm after the site visit." />
        </label>
        <div className={`leave-sum ${mins <= 0 ? 'err' : ''}`}><Icon name="clock" /><span>
          {mins <= 0 ? "'Out' must be after 'In'" : <>Counts as <b>{fmtDuration(mins)}</b> at work that day, once approved.</>}
        </span></div>
        <small className="dim">Your manager or a director approves it. Payroll uses the earliest in and latest out of your device punches and this fix.</small>
        {error && <div className="inline err">{error}</div>}
        <div className="modal-actions">
          <button type="button" className="btn ghost" onClick={onClose}>Cancel</button>
          <button className="btn primary" disabled={busy || mins <= 0 || !reason.trim()}>{busy ? 'Sending…' : 'Send request'}</button>
        </div>
      </form>
    </Modal>
  );
}

/** Employee: request fixes and see their status. */
export function MyPunchFixes({ me }) {
  const [list, setList] = useState([]);
  const [open, setOpen] = useState(false);
  const [notice, setNotice] = useState(null);
  const [error, setError] = useState(null);
  const load = useCallback(async () => {
    try { setList((await call('GET', '/api/punch-fixes/my', null, me.token)).fixes || []); }
    catch (e) { setError(e.message); }
  }, [me.token]);
  useEffect(() => { load(); }, [load]);
  const flash = (m) => { setNotice(m); setTimeout(() => setNotice(null), 5000); };
  const cancel = async (f) => {
    if (!window.confirm(`Withdraw your fix for ${fmtDay(f.day)}?`)) return;
    try { await call('POST', `/api/punch-fixes/${f.id}/cancel`, {}, me.token); flash('Request withdrawn'); load(); }
    catch (e) { setError(e.message); }
  };

  return (
    <section className="panel">
      <Toasts notice={notice} error={error} onClose={() => setError(null)} />
      <div className="panel-head">
        <h3>Missed punches</h3>
        <button className="btn primary" onClick={() => setOpen(true)}><Icon name="plus" />Fix a missed punch</button>
      </div>
      {!list.length ? <p className="dim">Forgot to punch in or out? Ask for a fix here — it counts for payroll once approved.</p> : (
        <ul className="perf-list">
          {list.slice(0, 10).map((f) => (
            <li key={f.id}>
              <span><b>{fmtDay(f.day)} · {f.in}–{f.out}</b>
                <small>{f.reason}</small>
                {f.status !== 'pending' && f.status !== 'cancelled' && (
                  <small className={f.status === 'rejected' ? 'pay-rejected' : ''}>
                    {f.status} by {f.decidedBy || '—'} {fmtStamp(f.decidedAt)}{f.decisionNote ? ` — “${f.decisionNote}”` : ''}</small>
                )}
              </span>
              <span className="leave-act">
                <span className={`pill ${PILL[f.status]}`}>{f.status}</span>
                {f.status === 'pending' && <button className="btn ghost small" onClick={() => cancel(f)}>Withdraw</button>}
              </span>
            </li>
          ))}
        </ul>
      )}
      {open && <FixDialog me={me} onClose={() => setOpen(false)} onDone={(m) => { setOpen(false); flash(m); load(); }} />}
    </section>
  );
}

function RejectFix({ me, fix, onClose, onDone }) {
  const [note, setNote] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError(null);
    try { await call('POST', `/api/punch-fixes/${fix.id}/reject`, { note }, me.token); onDone(`Rejected ${fix.name}'s punch fix`); }
    catch (err) { setError(err.message); }
    finally { setBusy(false); }
  };
  return (
    <Modal title="Reject punch fix" eyebrow={fix.name} onClose={onClose}>
      <form className="person-form" onSubmit={submit}>
        <p className="muted">{fmtDay(fix.day)} · {fix.in}–{fix.out} — “{fix.reason}”</p>
        <label className="field"><span>Reason (shown to {fix.name.split(' ')[0]})</span>
          <textarea rows={3} maxLength={500} required value={note} onChange={(e) => setNote(e.target.value)} autoFocus
                    placeholder="e.g. You were on the client site — apply for leave instead." />
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

/** Managers / directors: pending fixes they can decide (never their own). */
export function PunchFixQueue({ me, fixes, reload }) {
  const [rejecting, setRejecting] = useState(null);
  const [busy, setBusy] = useState(null);
  const [notice, setNotice] = useState(null);
  const [error, setError] = useState(null);
  const pending = fixes.filter((f) => f.status === 'pending' && f.accountId !== me.id)
    .sort((a, b) => a.day.localeCompare(b.day));
  const flash = (m) => { setNotice(m); setTimeout(() => setNotice(null), 5000); };
  const approve = async (f) => {
    setBusy(f.id);
    try { await call('POST', `/api/punch-fixes/${f.id}/approve`, {}, me.token); flash(`Approved ${f.name}'s punch fix`); reload(); }
    catch (e) { setError(e.message); }
    finally { setBusy(null); }
  };
  if (!pending.length) return null;

  return (
    <section className="panel">
      <Toasts notice={notice} error={error} onClose={() => setError(null)} />
      <div className="panel-head"><h3>Punch fixes waiting</h3><span className="muted">{pending.length} to decide · used by payroll once approved</span></div>
      <div className="leave-cards">
        {pending.map((f) => (
          <div key={f.id} className="leave-card">
            <div className="who"><Avatar label={f.name} /><span>{f.name}<small>asked {fmtStamp(f.createdAt)}</small></span></div>
            <div className="leave-what"><b>{fmtDay(f.day)}</b><span className="muted">{f.in}–{f.out} · {fmtDuration(span(f.in, f.out))}</span></div>
            <p title={f.reason}>{f.reason}</p>
            <div className="leave-act">
              <button className="btn danger small" onClick={() => setRejecting(f)}>Reject</button>
              <button className="btn primary small" disabled={busy === f.id} onClick={() => approve(f)}><Icon name="approve" size={15} />Approve</button>
            </div>
          </div>
        ))}
      </div>
      {rejecting && <RejectFix me={me} fix={rejecting} onClose={() => setRejecting(null)}
                               onDone={(m) => { setRejecting(null); flash(m); reload(); }} />}
    </section>
  );
}

export const pendingFixCount = (fixes, me) => fixes.filter((f) => f.status === 'pending' && f.accountId !== me.id).length;
