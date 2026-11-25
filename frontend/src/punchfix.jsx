/*
 * Missed-punch requests — employees ask, their reporting manager (or a director) approves; payroll uses
 * approved fixes and the punch history views show them as "added by X on request". A manager's own request goes
 * to their reporting manager the same way. Days with an in but no out (or the reverse) are listed here
 * and in the notifications bell until a fix is asked for.
 */

import { useCallback, useEffect, useState } from 'react';
import { Avatar, Icon, Modal, Toasts, call, fmtDuration } from './ui.jsx';

const PILL = { pending: 'warn', approved: 'live', rejected: 'bad', cancelled: 'idle' };
const STATUS = { pending: 'pending', approved: 'approved', rejected: 'declined', cancelled: 'withdrawn' };

/** What happened? — picked from a list; the request stores the label (+ any details) as its reason. */
const REASONS = [
  ['out', 'Forgot to punch out in the evening'],
  ['in', 'Forgot to punch in in the morning'],
  ['both', 'Forgot to punch in and out'],
  ['device', "The device didn't record my punch"],
  ['site', 'Was on a site / client visit'],
  ['other', 'Other (explain below)'],
];
const fmtDay = (s) => new Date(`${s}T00:00:00Z`).toLocaleDateString('en-GB', { weekday: 'short', day: '2-digit', month: 'short', year: 'numeric', timeZone: 'UTC' });
const fmtStamp = (ms) => (ms ? new Date(ms).toLocaleString('en-GB', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' }) : '—');
const span = (i, o) => {
  const [a, b] = [i, o].map((t) => +t.slice(0, 2) * 60 + +t.slice(3, 5));
  return Math.max(0, b - a);
};
const yesterday = () => { const d = new Date(); d.setDate(d.getDate() - 1); return d.toLocaleDateString('sv'); };

/** Times for one lone device punch, depending on which punch was missed. */
const around = (punch, kind) => (kind === 'in' ? { in: '09:00', out: punch } : { in: punch, out: '18:00' });

/**
 * Best guess for an incomplete day: a lone punch before 1 pm was the check-in (so the out is missing),
 * a later one was the check-out (the in is missing). With 3+ punches keep the first and the last.
 */
const guess = (punches) => {
  const first = punches[0], last = punches[punches.length - 1];
  if (punches.length > 1) return { in: first, out: last > '13:00' ? last : '18:00', kind: 'out' };
  const kind = first < '13:00' ? 'out' : 'in';
  return { ...around(first, kind), kind };
};

function FixDialog({ me, missing, onClose, onDone }) {
  const start = missing ? guess(missing.punches) : { in: '09:00', out: '18:00', kind: '' };
  const lone = missing?.punches.length === 1 ? missing.punches[0] : null;
  const [day, setDay] = useState(missing?.day || yesterday());
  const [inT, setIn] = useState(start.in);
  const [outT, setOut] = useState(start.out);
  const [kind, setKind] = useState(start.kind);
  const [details, setDetails] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const mins = inT && outT ? span(inT, outT) : 0;
  const label = REASONS.find(([k]) => k === kind)?.[1];
  const extra = details.trim();
  const reason = kind === 'other' ? extra : label ? `${label}${extra ? ` — ${extra}` : ''}` : '';

  const pick = (k) => {
    setKind(k);
    // one device punch: which one was missed decides whether it is the in or the out
    if (lone && (k === 'in' || k === 'out')) { const t = around(lone, k); setIn(t.in); setOut(t.out); }
  };

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError(null);
    try {
      await call('POST', '/api/punch-fixes', { day, in: inT, out: outT, reason }, me.token);
      onDone('Sent to your reporting manager');
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  };

  return (
    <Modal title="Fix a missed punch" eyebrow="Attendance" onClose={onClose}>
      <form className="person-form" onSubmit={submit}>
        {missing && <p className="muted">The device has {missing.punches.length === 1 ? 'only one punch' : `${missing.punches.length} punches`} on this day
          ({missing.punches.join(', ')}). Check the times below — the one you missed is filled in with a usual time.</p>}
        <label className="field"><span>Day</span>
          <input type="date" required max={yesterday()} value={day} onChange={(e) => setDay(e.target.value)} />
        </label>
        <div className="form-grid">
          <label className="field"><span>In</span><input type="time" required value={inT} onChange={(e) => setIn(e.target.value)} /></label>
          <label className="field"><span>Out</span><input type="time" required value={outT} onChange={(e) => setOut(e.target.value)} /></label>
        </div>
        <label className="field"><span>What happened?</span>
          <select required value={kind} onChange={(e) => pick(e.target.value)}>
            <option value="" disabled>Choose what happened…</option>
            {REASONS.map(([k, l]) => <option key={k} value={k}>{l}</option>)}
          </select>
        </label>
        <label className="field"><span>{kind === 'other' ? 'Explain what happened' : 'More details (optional)'}</span>
          <textarea rows={2} maxLength={400} required={kind === 'other'} value={details} onChange={(e) => setDetails(e.target.value)}
                    placeholder={kind === 'other' ? 'e.g. Power cut at the gate in the evening.' : 'e.g. Left at 6 pm after the site visit.'} />
        </label>
        <div className={`leave-sum ${mins <= 0 ? 'err' : ''}`}><Icon name="clock" /><span>
          {mins <= 0 ? "'Out' must be after 'In'" : <>Counts as <b>{fmtDuration(mins)}</b> at work that day, once approved.</>}
        </span></div>
        <small className="dim">Your reporting manager (or a director) approves it; managers ask their own reporting manager. Once approved it shows in your punch history as added by them on your request, and payroll uses the earliest in and latest out of your device punches and this fix.</small>
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
export function MyPunchFixes({ me, onLoaded }) {
  const [list, setList] = useState([]);
  const [missing, setMissing] = useState([]);
  const [open, setOpen] = useState(false); // false | true (blank) | a missing day
  const [notice, setNotice] = useState(null);
  const [error, setError] = useState(null);
  const load = useCallback(async () => {
    try {
      const [f, m] = await Promise.all([
        call('GET', '/api/punch-fixes/my', null, me.token),
        call('GET', '/api/punch-fixes/missing', null, me.token),
      ]);
      setList(f.fixes || []);
      setMissing(m.days || []);
      onLoaded?.(f.fixes || []);
    } catch (e) { setError(e.message); }
  }, [me.token, onLoaded]);
  useEffect(() => { load(); }, [load]);
  const flash = (m) => { setNotice(m); setTimeout(() => setNotice(null), 5000); };
  const cancel = async (f) => {
    if (!window.confirm(`Withdraw your fix for ${fmtDay(f.day)}?`)) return;
    try { await call('POST', `/api/punch-fixes/${f.id}/cancel`, {}, me.token); flash('Request withdrawn'); load(); }
    catch (e) { setError(e.message); }
  };

  // Toasts and the dialog stay outside .panel: its backdrop-filter would trap their position: fixed.
  return (<>
    <Toasts notice={notice} error={error} onClose={() => setError(null)} />
    <section className="panel">
      <div className="panel-head">
        <h3>Missed punches</h3>
        <button className="btn primary" onClick={() => setOpen(true)}><Icon name="plus" />Fix a missed punch</button>
      </div>
      {missing.length > 0 && (
        <div className="missing-box">
          <b><Icon name="clock" size={15} />You didn’t punch properly on {missing.length} day{missing.length === 1 ? '' : 's'}</b>
          <small className="dim">An in without an out, or an out without an in. Ask your reporting manager to add the missing punch.</small>
          <ul className="perf-list">
            {missing.map((d) => (
              <li key={d.day}>
                <span><b>{fmtDay(d.day)}</b><small>device: {d.punches.join(', ')} · {d.punches.length === 1 ? 'one punch only' : `${d.punches.length} punches`}</small></span>
                <button className="btn primary small" onClick={() => setOpen(d)}>Request fix</button>
              </li>
            ))}
          </ul>
        </div>
      )}
      {!list.length ? <p className="dim">Forgot to punch in or out? Ask for a fix here — it counts for payroll once approved.</p> : (
        <ul className="perf-list">
          {list.slice(0, 10).map((f) => (
            <li key={f.id}>
              <span><b>{fmtDay(f.day)} · {f.in}–{f.out}</b>
                <small>{f.reason}</small>
                {f.status !== 'pending' && f.status !== 'cancelled' && (
                  <small className={f.status === 'rejected' ? 'pay-rejected' : ''}>
                    {STATUS[f.status]} by {f.decidedBy || '—'} {fmtStamp(f.decidedAt)}{f.decisionNote ? ` — “${f.decisionNote}”` : ''}</small>
                )}
              </span>
              <span className="leave-act">
                <span className={`pill ${PILL[f.status]}`}>{STATUS[f.status]}</span>
                {f.status === 'pending' && <button className="btn ghost small" onClick={() => cancel(f)}>Withdraw</button>}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
    {open && <FixDialog key={open === true ? 'new' : open.day} me={me} missing={open === true ? null : open}
                        onClose={() => setOpen(false)} onDone={(m) => { setOpen(false); flash(m); load(); }} />}
  </>);
}

/**
 * Decline (reason required) or comment on a fix. "Comment" writes a note and then approves or declines
 * with it; the note is the decision note the employee sees.
 */
function DecideFix({ me, fix, mode, onClose, onDone }) {
  const [note, setNote] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(null);
  const first = fix.name.split(' ')[0];
  const decide = async (approve) => {
    setBusy(approve ? 'approve' : 'decline'); setError(null);
    try {
      await call('POST', `/api/punch-fixes/${fix.id}/${approve ? 'approve' : 'reject'}`, { note }, me.token);
      onDone(`${approve ? 'Approved' : 'Declined'} ${fix.name}'s punch fix`);
    } catch (err) { setError(err.message); setBusy(null); }
  };
  const submit = (e) => { e.preventDefault(); decide(mode === 'comment'); };
  return (
    <Modal title={mode === 'comment' ? 'Comment on punch fix' : 'Decline punch fix'} eyebrow={fix.name} onClose={onClose}>
      <form className="person-form" onSubmit={submit}>
        <p className="muted">{fmtDay(fix.day)} · {fix.in}–{fix.out} — “{fix.reason}”</p>
        <label className="field"><span>{mode === 'comment' ? 'Comment' : 'Reason'} (shown to {first})</span>
          <textarea rows={3} maxLength={500} required value={note} onChange={(e) => setNote(e.target.value)} autoFocus
                    placeholder={mode === 'comment' ? 'e.g. OK this time — please punch out before leaving.'
                                                    : 'e.g. You were on the client site — apply for leave instead.'} />
        </label>
        {error && <div className="inline err">{error}</div>}
        <div className="modal-actions">
          <button type="button" className="btn ghost" onClick={onClose}>Back</button>
          {mode === 'comment' ? (<>
            <button type="button" className="btn danger" disabled={!!busy || !note.trim()} onClick={() => decide(false)}>
              {busy === 'decline' ? 'Saving…' : 'Decline'}</button>
            <button className="btn primary" disabled={!!busy || !note.trim()}>
              <Icon name="approve" size={15} />{busy === 'approve' ? 'Saving…' : 'Approve with comment'}</button>
          </>) : (
            <button className="btn danger" disabled={!!busy || !note.trim()}>{busy ? 'Saving…' : 'Decline'}</button>
          )}
        </div>
      </form>
    </Modal>
  );
}

/** Managers / directors: pending fixes they can decide (never their own). */
export function PunchFixQueue({ me, fixes, reload }) {
  const [deciding, setDeciding] = useState(null); // { fix, mode: 'decline' | 'comment' }
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

  return (<>
    <Toasts notice={notice} error={error} onClose={() => setError(null)} />
    <section className="panel">
      <div className="panel-head"><h3>Punch fixes waiting</h3><span className="muted">{pending.length} to decide · used by payroll once approved</span></div>
      <div className="leave-cards">
        {pending.map((f) => (
          <div key={f.id} className="leave-card">
            <div className="who"><Avatar label={f.name} /><span>{f.name}<small>asked {fmtStamp(f.createdAt)}</small></span></div>
            <div className="leave-what"><b>{fmtDay(f.day)}</b><span className="muted">{f.in}–{f.out} · {fmtDuration(span(f.in, f.out))}</span></div>
            <p title={f.reason}>{f.reason}</p>
            <div className="leave-act">
              <button className="btn ghost small" onClick={() => setDeciding({ fix: f, mode: 'comment' })}><Icon name="chat" size={15} />Comment</button>
              <button className="btn danger small" onClick={() => setDeciding({ fix: f, mode: 'decline' })}>Decline</button>
              <button className="btn primary small" disabled={busy === f.id} onClick={() => approve(f)}><Icon name="approve" size={15} />Approve</button>
            </div>
          </div>
        ))}
      </div>
    </section>
    {deciding && <DecideFix me={me} fix={deciding.fix} mode={deciding.mode} onClose={() => setDeciding(null)}
                            onDone={(m) => { setDeciding(null); flash(m); reload(); }} />}
  </>);
}

export const pendingFixCount = (fixes, me) => fixes.filter((f) => f.status === 'pending' && f.accountId !== me.id).length;
