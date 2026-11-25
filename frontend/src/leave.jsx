/* LTS — leave tracking: employee calendar/history/apply, approver queue. */

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Avatar, Empty, Icon, Modal, Stat, Toasts, call, todayStr } from './ui.jsx';

const CATS = {
  personal: { label: 'Personal', short: 'P' },
  sick: { label: 'Sick', short: 'S' },
};
const STATUS_PILL = { approved: 'live', pending: 'warn', rejected: 'bad', cancelled: 'idle' };
const HALF = { first: 'First half', second: 'Second half', late: 'Late arrival', early: 'Early departure' };
/** Late arrival / early departure: a few hours of one day, counted as hours / 8. */
const isShort = (h) => h === 'late' || h === 'early';
const HOURS_PER_DAY = 8;
const HOUR_STEPS = [0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4];
const fmtHours = (h) => `${h} h`;
/** Up to 2 decimals, no trailing zeros (0.3125 -> 0.31, 11.5 -> 11.5). */
const num = (n) => Math.round(n * 100) / 100;
const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

/* ------------------------------------------------------------- helpers */

/** 'yyyy-MM-dd' <-> Date at UTC midnight, so day arithmetic never drifts. */
const parse = (s) => new Date(`${s}T00:00:00Z`);
const iso = (d) => d.toISOString().slice(0, 10);
const addDays = (s, n) => { const d = parse(s); d.setUTCDate(d.getUTCDate() + n); return iso(d); };
const isSunday = (s) => parse(s).getUTCDay() === 0;

/** Company holidays (managed in Payroll): { 'yyyy-MM-dd': name }. Loaded once per view. */
function useHolidays(me) {
  const [map, setMap] = useState({});
  useEffect(() => {
    call('GET', '/api/payroll/holidays', null, me.token)
      .then((d) => setMap(Object.fromEntries((d.holidays || []).map((h) => [h.day, h.name])))).catch(() => {});
  }, [me.token]);
  return map;
}

/** Mirrors the backend: Sundays and holidays don't count, a half-day is 0.5, late/early is hours / 8. */
export function countDays(from, to, half, hours = 0, holidays = {}) {
  if (!from || !to || to < from) return 0;
  let n = 0;
  for (let d = from; d <= to; d = addDays(d, 1)) if (!isSunday(d) && !holidays[d]) n++;
  if (!half) return n;
  return isShort(half) ? (n * hours) / HOURS_PER_DAY : n * 0.5;
}

const fmtDays = (n) => `${num(n)} day${num(n) === 1 ? '' : 's'}`;
/** How much a request takes: hours for late/early, days otherwise. */
const fmtAmount = (r) => (isShort(r.halfDay) ? `${fmtHours(r.hours)} (${fmtDays(r.days)})` : fmtDays(r.days));
const fmtDate = (s) => parse(s).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric', timeZone: 'UTC' });
const fmtShort = (s) => parse(s).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', timeZone: 'UTC' });
const fmtStamp = (ms) => ms ? new Date(ms).toLocaleString('en-GB', {
  day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit',
}) : null;

function range(r) {
  if (r.from === r.to) return `${fmtDate(r.from)}${r.halfDay ? ` · ${HALF[r.halfDay]}${isShort(r.halfDay) ? ` ${fmtHours(r.hours)}` : ''}` : ''}`;
  const sameYear = r.from.slice(0, 4) === r.to.slice(0, 4);
  return `${sameYear ? fmtShort(r.from) : fmtDate(r.from)} – ${fmtDate(r.to)}`;
}

/** The employee may withdraw while pending, or while approved and not yet started. */
const canSelfCancel = (r) => r.status === 'pending' || (r.status === 'approved' && r.from > todayStr());

/** Requests that are on (or partly on) a given day: approved or pending only. */
function onLeave(requests, day) {
  return requests.filter((r) => (r.status === 'approved' || r.status === 'pending') && r.from <= day && r.to >= day);
}

/* ------------------------------------------------------------- calendar */

function LeaveCalendar({ requests, holidays = {} }) {
  const [month, setMonth] = useState(() => todayStr().slice(0, 7)); // yyyy-MM
  const today = todayStr();

  // day -> request covering it (approved wins over pending)
  const byDay = useMemo(() => {
    const map = {};
    for (const r of requests) {
      if (r.status !== 'approved' && r.status !== 'pending') continue;
      for (let d = r.from; d <= r.to; d = addDays(d, 1)) {
        if (!map[d] || (map[d].status === 'pending' && r.status === 'approved')) map[d] = r;
      }
    }
    return map;
  }, [requests]);

  const first = `${month}-01`;
  const lead = (parse(first).getUTCDay() + 6) % 7; // Monday-first grid
  const daysInMonth = new Date(Date.UTC(+month.slice(0, 4), +month.slice(5, 7), 0)).getUTCDate();
  const cells = [
    ...Array.from({ length: lead }, () => null),
    ...Array.from({ length: daysInMonth }, (_, i) => `${month}-${String(i + 1).padStart(2, '0')}`),
  ];
  const shift = (n) => {
    const d = parse(first); d.setUTCMonth(d.getUTCMonth() + n); setMonth(iso(d).slice(0, 7));
  };
  const title = parse(first).toLocaleDateString('en-GB', { month: 'long', year: 'numeric', timeZone: 'UTC' });
  const monthDays = Object.entries(byDay)
    .filter(([d, r]) => d.startsWith(month) && r.status === 'approved' && !isSunday(d) && !holidays[d])
    .reduce((s, [, r]) => s + (r.halfDay ? r.days : 1), 0);

  return (
    <div className="cal">
      <div className="cal-head">
        <button className="icon-btn neutral" title="Previous month" onClick={() => shift(-1)}>‹</button>
        <div><b>{title}</b><span className="muted">{monthDays ? `${fmtDays(monthDays)} approved` : 'No approved leave'}</span></div>
        <button className="icon-btn neutral" title="Next month" onClick={() => shift(1)}>›</button>
        {month !== today.slice(0, 7) && <button className="btn ghost small" onClick={() => setMonth(today.slice(0, 7))}>Today</button>}
      </div>
      <div className="cal-grid">
        {WEEKDAYS.map((w) => <span key={w} className={`cal-wd ${w === 'Sun' ? 'sun' : ''}`}>{w}</span>)}
        {cells.map((d, i) => {
          if (!d) return <span key={`x${i}`} />;
          const r = byDay[d];
          const hol = holidays[d];
          const sun = isSunday(d) || !!hol; // both are paid days off, never counted as leave
          const cls = ['cal-day', sun && 'sun', hol && 'holiday', d === today && 'today',
            r && !sun && `leave ${r.category} ${r.status}`, r?.halfDay && 'half'].filter(Boolean).join(' ');
          const tip = r && !sun
            ? `${CATS[r.category].label} leave · ${r.status}${r.halfDay ? ` · ${HALF[r.halfDay]}${isShort(r.halfDay) ? ` ${fmtHours(r.hours)}` : ''}` : ''}\n${r.reason}`
            : hol ? `Holiday · ${hol}` : sun ? 'Sunday' : undefined;
          return (
            <div key={d} className={cls} title={tip}>
              <span>{+d.slice(8)}</span>
              {r && !sun && <em>{CATS[r.category].short}{isShort(r.halfDay) ? ` ${r.hours}h` : r.halfDay ? '½' : ''}</em>}
              {hol && <em className="hol" title={hol}>{hol.length > 9 ? `${hol.slice(0, 8)}…` : hol}</em>}
            </div>
          );
        })}
      </div>
      <div className="cal-legend">
        <span><i className="sw personal approved" />Personal</span>
        <span><i className="sw sick approved" />Sick</span>
        <span><i className="sw personal pending" />Pending</span>
        <span><i className="sw half" />Half-day / hours</span>
        <span><i className="sw sun" />Sunday (not counted)</span>
        <span><i className="sw holiday" />Holiday (not counted)</span>
      </div>
    </div>
  );
}

/* ---------------------------------------------------------------- apply */

function LeaveForm({ me, balance, holidays = {}, onClose, onSaved }) {
  const [category, setCategory] = useState('personal');
  const [from, setFrom] = useState(todayStr());
  const [toDate, setTo] = useState(todayStr());
  const [half, setHalf] = useState('');
  const [hours, setHours] = useState(1);
  const [reason, setReason] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  // Full day picks a From–To range; every other duration is one date
  const halfDay = half;
  const to = halfDay ? from : toDate;
  const shortHours = isShort(halfDay) ? hours : 0;
  const days = countDays(from, to, halfDay, shortHours, holidays);
  const left = balance.find((b) => b.category === category)?.remaining ?? 0;
  const crossesYear = from && to && from.slice(0, 4) !== to.slice(0, 4);
  const problem = !from || !to ? null
    : to < from ? "'To' date is before 'From' date"
    : crossesYear ? 'Leave can’t cross into a new year — file one request per year'
    : days === 0 ? 'Those dates are all Sundays or holidays — no leave needed'
    : days > left ? `Only ${fmtDays(left)} of ${CATS[category].label.toLowerCase()} leave left`
    : null;

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError(null);
    try {
      await call('POST', '/api/leave', { category, from, to, halfDay, hours: shortHours || null, reason }, me.token);
      onSaved(`Leave request sent for ${shortHours ? `${HALF[halfDay].toLowerCase()} of ${fmtHours(shortHours)}` : fmtDays(days)}`);
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  };

  return (
    <Modal title="Request leave" eyebrow="New request" onClose={onClose}>
      <form className="person-form" onSubmit={submit}>
        <fieldset className="field">
          <span>Category</span>
          <div className="cat-picks">
            {Object.entries(CATS).map(([k, c]) => {
              const b = balance.find((x) => x.category === k);
              return (
                <label key={k} className={`cat-pick ${k} ${category === k ? 'on' : ''}`}>
                  <input type="radio" name="category" checked={category === k} onChange={() => setCategory(k)} />
                  <b>{c.label}</b>
                  <small>{b ? `${num(b.remaining)} of ${b.quota} left` : ''}</small>
                </label>
              );
            })}
          </div>
        </fieldset>

        <fieldset className="field">
          <span>Duration</span>
          <div className="role-picks">
            {[['', 'Full day'], ['first', 'First half'], ['second', 'Second half'],
              ['late', 'Late arrival'], ['early', 'Early departure']].map(([k, label]) => (
              <label key={k || 'full'} className={`role-pick ${half === k ? 'on' : ''}`}>
                <input type="radio" name="half" checked={half === k} onChange={() => setHalf(k)} />{label}
              </label>
            ))}
          </div>
        </fieldset>

        {half === '' ? (
          <div className="form-grid">
            <label className="field">
              <span>From</span>
              <input type="date" value={from} required
                     onChange={(e) => { setFrom(e.target.value); if (!toDate || toDate < e.target.value) setTo(e.target.value); }} />
            </label>
            <label className="field">
              <span>To</span>
              <input type="date" value={toDate} min={from} required onChange={(e) => setTo(e.target.value)} />
            </label>
          </div>
        ) : (
          <label className="field">
            <span>Date</span>
            <input type="date" value={from} required
                   onChange={(e) => { setFrom(e.target.value); if (!toDate || toDate < e.target.value) setTo(e.target.value); }} />
          </label>
        )}

        {isShort(half) && (
          <label className="field">
            <span>{half === 'late' ? 'How many hours late?' : 'How many hours early?'}</span>
            <select value={hours} onChange={(e) => setHours(Number(e.target.value))}>
              {HOUR_STEPS.map((h) => <option key={h} value={h}>{fmtHours(h)}</option>)}
            </select>
            <small className="dim">Counted as {hours} ÷ {HOURS_PER_DAY} = {fmtDays(hours / HOURS_PER_DAY)} of {CATS[category].label.toLowerCase()} leave.</small>
          </label>
        )}

        <label className="field">
          <span>Details</span>
          <textarea rows={4} maxLength={1000} required value={reason} onChange={(e) => setReason(e.target.value)}
                    placeholder="What's the leave for? Anything your manager should know." />
        </label>

        <div className={`leave-sum ${problem ? 'err' : ''}`}>
          <Icon name="calendar" />
          <span>{problem || <>
            <b>{shortHours ? `${fmtHours(shortHours)} · ${fmtDays(days)}` : fmtDays(days)}</b> of {CATS[category].label.toLowerCase()} leave
            {countDays(from, to, '', 0, holidays) < (parse(to) - parse(from)) / 864e5 + 1 ? ' (Sundays / holidays skipped)' : ''}
            {' '}· {fmtDays(left - days)} left after this
          </>}</span>
        </div>

        {error && <div className="inline err">{error}</div>}
        <div className="modal-actions">
          <button type="button" className="btn ghost" onClick={onClose}>Cancel</button>
          <button className="btn primary" disabled={busy || !!problem || !reason.trim()}>{busy ? 'Sending…' : 'Send request'}</button>
        </div>
      </form>
    </Modal>
  );
}

/* ------------------------------------------------------- approver balance */

/**
 * (accountId, year) -> per-category balance, from the usage rows the
 * team/all endpoints return. remaining counts pending requests as booked,
 * the same way the employee's own tiles do.
 */
export function makeBalanceOf(usage, quotas, personQuotas) {
  const rows = new Map();
  for (const u of usage || []) rows.set(`${u.accountId}|${u.year}|${u.category}`, u);
  const own = new Map();
  for (const q of personQuotas || []) own.set(`${q.accountId}|${q.category}`, q);
  return (accountId, year) => Object.keys(CATS).map((c) => {
    const u = rows.get(`${accountId}|${year}|${c}`);
    const mine = own.get(`${accountId}|${c}`);
    const quota = mine ? mine.days : quotas?.[c] ?? 0;
    const used = u?.used ?? 0;
    const pending = u?.pending ?? 0;
    return {
      category: c, quota, used, pending, remaining: quota - used - pending,
      custom: !!mine, companyQuota: quotas?.[c] ?? 0, setBy: mine?.setBy, setAt: mine?.setAt,
    };
  });
}

/**
 * One person's yearly days, replacing the company default for them. A
 * director (anyone) or their manager (reportees) sets it; never your own.
 * balance = that person's rows from makeBalanceOf.
 */
export function PersonQuotaDialog({ me, person, balance, onClose, onDone }) {
  const name = person.name || person.email;
  const [q, setQ] = useState(() => Object.fromEntries(balance.map((b) => [b.category, b.custom ? String(b.quota) : ''])));
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError(null);
    try {
      // empty = back to the company default
      const body = Object.fromEntries(Object.keys(CATS).map((c) => [c, q[c] === '' ? null : Number(q[c])]));
      await call('PUT', `/api/leave/quotas/${person.id}`, body, me.token);
      onDone(`Leave quota updated for ${name}`);
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  };

  return (
    <Modal title="Leave quota" eyebrow={name} onClose={onClose}>
      <form className="person-form" onSubmit={submit}>
        <div className="form-grid">
          {balance.map((b) => (
            <label key={b.category} className="field">
              <span>{CATS[b.category].label} days / year</span>
              <input type="number" min="0" max="365" step="0.5" value={q[b.category]}
                     placeholder={`${b.companyQuota} (company default)`}
                     onChange={(e) => setQ({ ...q, [b.category]: e.target.value })} />
              <small className="dim">{num(b.used)} taken · {num(b.pending)} pending this year
                {b.custom && b.setBy ? ` · set by ${b.setBy}` : ''}</small>
            </label>
          ))}
        </div>
        <small className="dim">Leave a box empty to use the company default. Applies to this year and later; leave already approved stays approved.</small>
        {error && <div className="inline err">{error}</div>}
        <div className="modal-actions">
          <button type="button" className="btn ghost" onClick={onClose}>Cancel</button>
          <button className="btn primary" disabled={busy}>{busy ? 'Saving…' : 'Save quota'}</button>
        </div>
      </form>
    </Modal>
  );
}

/** "Personal 8 / 12 left · Sick 5 / 6 left" for one employee, in the request's year. */
function BalanceChips({ balanceOf, r }) {
  if (!balanceOf) return null;
  const year = +r.from.slice(0, 4);
  return (
    <div className="bal-chips" title={`Leave left in ${year}, counting pending requests (including this one)`}>
      {balanceOf(r.accountId, year).map((b) => (
        <span key={b.category} className={`bal-chip ${b.category} ${b.remaining <= 0 ? 'out' : ''}`}>
          {CATS[b.category].label} <b>{num(b.remaining)}</b> / {b.quota} left
          {b.pending > 0 && <small> · {num(b.pending)} pending</small>}
        </span>
      ))}
    </div>
  );
}

/* -------------------------------------------------------------- history */

function StatusPill({ r }) {
  return <span className={`pill ${STATUS_PILL[r.status]}`}>{r.status}</span>;
}

function Decision({ r }) {
  if (r.status === 'cancelled') {
    return <><span className="mono dim">{fmtStamp(r.cancelledAt)}</span><small className="sub">cancelled by {r.cancelledBy || '—'}</small></>;
  }
  if (!r.decidedAt) return <span className="dim">—</span>;
  return <span className="mono">{fmtStamp(r.decidedAt)}</span>;
}

/**
 * Leave history table. showName adds the person column (approver views);
 * actions(r) returns the buttons for a row.
 */
function LeaveTable({ requests, showName, actions, empty }) {
  if (!requests.length) return <Empty title={empty} hint="Requests show up here once they're sent." />;
  return (
    <div className="table-wrap">
      <table className="leave-table">
        <thead>
          <tr>
            {showName && <th>Person</th>}
            <th>Leave</th>
            <th>Days</th>
            <th>Applied on</th>
            <th>Status</th>
            <th>Decided on</th>
            <th>Decided by</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {requests.map((r) => (
            <tr key={r.id}>
              {showName && (
                <td><div className="who"><Avatar label={r.name} /><span>{r.name}<small>{r.email}</small></span></div></td>
              )}
              <td>
                <div className="leave-what">
                  <span className={`cat-tag ${r.category}`}>{CATS[r.category].label}</span>
                  <b>{range(r)}</b>
                </div>
                <small className="sub reason" title={r.reason}>{r.reason}</small>
              </td>
              <td className="mono strong">{isShort(r.halfDay) ? <>{fmtHours(r.hours)}<small className="sub">{num(r.days)} day</small></> : num(r.days)}</td>
              <td className="mono dim">{fmtStamp(r.appliedAt)}</td>
              <td><StatusPill r={r} /></td>
              <td><Decision r={r} /></td>
              <td>
                {r.decidedBy || <span className="dim">—</span>}
                {r.note && <small className="sub reason" title={r.note}>“{r.note}”</small>}
              </td>
              <td className="row-act">{actions?.(r)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function BalanceStats({ balance, year }) {
  const icon = { personal: 'user', sick: 'plus' };
  return (
    <div className="stats">
      {balance.map((b) => (
        <Stat key={b.category} label={`${CATS[b.category].label} leave left`} value={num(b.remaining)} unit={` / ${b.quota}`}
              accent={b.remaining <= 0 ? 'alert' : b.category === 'personal' ? 'cyan' : 'saffron'} icon={icon[b.category]}
              foot={`${num(b.used)} taken · ${num(b.pending)} pending · ${year}${b.custom ? ' · your own quota' : ''}`} />
      ))}
    </div>
  );
}

/* ------------------------------------------------------------- employee */

/** The Leave tab's two balance tiles, for the employee's "My dashboard". */
export function LeaveBalance({ me }) {
  const [data, setData] = useState(null);
  useEffect(() => {
    call('GET', '/api/leave/my', null, me.token).then(setData).catch(() => setData(null));
  }, [me.token]);
  if (!data?.balance?.length) return null;
  return <BalanceStats balance={data.balance} year={data.year} />;
}

/** Employee "Leave" tab: balance, calendar, history, new request. */
export function EmployeeLeave({ me }) {
  const [data, setData] = useState({ requests: [], balance: [], year: null });
  const holidays = useHolidays(me);
  const upcoming = Object.entries(holidays).filter(([d]) => d >= todayStr()).sort().slice(0, 4);
  const [form, setForm] = useState(false);
  const [notice, setNotice] = useState(null);
  const [error, setError] = useState(null);

  const load = useCallback(async () => {
    try { setData(await call('GET', '/api/leave/my', null, me.token)); setError(null); }
    catch (e) { setError(e.message); }
  }, [me.token]);
  useEffect(() => { load(); }, [load]);

  const flash = (m) => { setNotice(m); setTimeout(() => setNotice(null), 5000); };
  const cancel = async (r) => {
    if (!window.confirm(`Cancel your ${CATS[r.category].label.toLowerCase()} leave for ${range(r)}?`)) return;
    try { await call('POST', `/api/leave/${r.id}/cancel`, {}, me.token); flash('Leave cancelled'); load(); }
    catch (e) { setError(e.message); }
  };

  return (<>
    <Toasts notice={notice} error={error} onClose={() => setError(null)} />
    <BalanceStats balance={data.balance} year={data.year} />

    <section className="panel">
      <div className="panel-head">
        <h3>Leave calendar</h3>
        <button className="btn primary" onClick={() => setForm(true)}><Icon name="plus" />Request leave</button>
      </div>
      <LeaveCalendar requests={data.requests} holidays={holidays} />
      {upcoming.length > 0 && (
        <div className="hol-next"><Icon name="calendar" size={15} />Upcoming holidays:
          {upcoming.map(([d, n]) => <span key={d}><b>{n}</b> {fmtShort(d)}</span>)}</div>
      )}
    </section>

    <section className="panel">
      <div className="panel-head"><h3>Leave history</h3><span className="muted">{data.requests.length} request(s)</span></div>
      <LeaveTable requests={data.requests} empty="No leave requested yet"
                  actions={(r) => canSelfCancel(r) && (
                    <button className="btn ghost small" onClick={() => cancel(r)}>Cancel</button>
                  )} />
    </section>

    {form && <LeaveForm me={me} balance={data.balance} holidays={holidays} onClose={() => setForm(false)}
                        onSaved={(m) => { setForm(false); flash(m); load(); }} />}
  </>);
}

/* ------------------------------------------------------------ approvers */

function DecideDialog({ me, req, approve, balanceOf, onClose, onDone }) {
  const [note, setNote] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const verb = approve ? 'Approve' : 'Reject';

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError(null);
    try {
      await call('POST', `/api/leave/${req.id}/${approve ? 'approve' : 'reject'}`, { note }, me.token);
      onDone(`${approve ? 'Approved' : 'Rejected'} ${req.name}'s leave`);
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  };

  return (
    <Modal title={`${verb} leave`} eyebrow={req.name} onClose={onClose}>
      <form className="person-form" onSubmit={submit}>
        <div className="leave-card flat">
          <div className="leave-what"><span className={`cat-tag ${req.category}`}>{CATS[req.category].label}</span><b>{range(req)}</b><span className="muted">{fmtAmount(req)}</span></div>
          <p>{req.reason}</p>
          <BalanceChips balanceOf={balanceOf} r={req} />
        </div>
        <label className="field">
          <span>Note to {req.name.split(' ')[0]} {approve && <em className="dim">(optional)</em>}</span>
          <textarea rows={3} maxLength={1000} value={note} onChange={(e) => setNote(e.target.value)}
                    placeholder={approve ? 'e.g. Hand over the survey batch to Ravi first.' : 'Why it can’t be approved'} autoFocus />
        </label>
        {error && <div className="inline err">{error}</div>}
        <div className="modal-actions">
          <button type="button" className="btn ghost" onClick={onClose}>Back</button>
          <button className={`btn ${approve ? 'primary' : 'danger'}`} disabled={busy}>{busy ? 'Saving…' : verb}</button>
        </div>
      </form>
    </Modal>
  );
}

function QuotaDialog({ me, onClose, onDone }) {
  const [q, setQ] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    call('GET', '/api/leave/quotas', null, me.token).then((d) => setQ(d.quotas)).catch((e) => setError(e.message));
  }, [me.token]);

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError(null);
    try {
      await call('PUT', '/api/leave/quotas', { personal: Number(q.personal), sick: Number(q.sick) }, me.token);
      onDone('Leave quotas updated');
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  };

  return (
    <Modal title="Yearly leave quotas" eyebrow="Company-wide" onClose={onClose}>
      {!q ? (error ? <div className="inline err">{error}</div> : <p className="muted">Loading…</p>) : (
        <form className="person-form" onSubmit={submit}>
          <div className="form-grid">
            {Object.entries(CATS).map(([k, c]) => (
              <label key={k} className="field">
                <span>{c.label} days / year</span>
                <input type="number" min="0" max="365" step="0.5" required value={q[k]}
                       onChange={(e) => setQ({ ...q, [k]: e.target.value })} />
              </label>
            ))}
          </div>
          <small className="dim">Applies to everyone without a personal quota, for the current and future years. Set a personal quota from a person’s details in People. Requests already approved stay approved.</small>
          {error && <div className="inline err">{error}</div>}
          <div className="modal-actions">
            <button type="button" className="btn ghost" onClick={onClose}>Cancel</button>
            <button className="btn primary" disabled={busy}>{busy ? 'Saving…' : 'Save quotas'}</button>
          </div>
        </form>
      )}
    </Modal>
  );
}

/**
 * Compact leave panel for the director's Dashboard: the pending queue with the
 * same Approve / Reject actions as the Leave tab, who's away, and shortcuts.
 * The full Leave tab (OpsLeave) is unchanged.
 */
export function DashboardLeave({ me, requests, balanceOf, reload, onOpenAll }) {
  const [deciding, setDeciding] = useState(null); // { req, approve }
  const [quotas, setQuotas] = useState(false);
  const [notice, setNotice] = useState(null);
  const [error, setError] = useState(null);
  const SHOW = 4;

  const flash = (m) => { setNotice(m); setTimeout(() => setNotice(null), 5000); };
  const today = todayStr();
  const pending = requests.filter((r) => r.status === 'pending' && r.accountId !== me.id)
    .sort((a, b) => a.from.localeCompare(b.from));
  const away = isSunday(today) ? [] : onLeave(requests, today).filter((r) => r.status === 'approved');
  const awayNames = [...new Set(away.map((r) => r.name.split(' ')[0]))];
  const upcoming = requests.filter((r) => r.status === 'approved' && r.from > today).length;

  return (<>
    <Toasts notice={notice} error={error} onClose={() => setError(null)} />
    <section className="panel">
      <div className="panel-head">
        <h3>Leave</h3>
        <div className="leave-act">
          <button className="btn ghost small" onClick={() => setQuotas(true)}><Icon name="edit" size={15} />Leave quotas</button>
          <button className="btn ghost small" onClick={onOpenAll}><Icon name="calendar" size={15} />All leave requests →</button>
        </div>
      </div>

      <div className="dash-leave-facts">
        <span className={pending.length ? 'warn' : ''}><b>{pending.length}</b> awaiting decision</span>
        <span><b>{awayNames.length}</b> on leave today{awayNames.length ? `: ${awayNames.join(', ')}` : ''}</span>
        <span><b>{upcoming}</b> upcoming approved</span>
      </div>

      {pending.length === 0 ? <Empty title="No pending leave" hint="New leave requests will appear here." /> : (<>
        <div className="leave-cards">
          {pending.slice(0, SHOW).map((r) => (
            <div key={r.id} className="leave-card">
              <div className="who"><Avatar label={r.name} /><span>{r.name}<small>applied {fmtStamp(r.appliedAt)}</small></span></div>
              <div className="leave-what"><span className={`cat-tag ${r.category}`}>{CATS[r.category].label}</span><b>{range(r)}</b><span className="muted">{fmtAmount(r)}</span></div>
              <p title={r.reason}>{r.reason}</p>
              <BalanceChips balanceOf={balanceOf} r={r} />
              <div className="leave-act">
                <button className="btn danger small" onClick={() => setDeciding({ req: r, approve: false })}>Reject</button>
                <button className="btn primary small" onClick={() => setDeciding({ req: r, approve: true })}><Icon name="approve" size={15} />Approve</button>
              </div>
            </div>
          ))}
        </div>
        {pending.length > SHOW && (
          <button className="link dash-leave-more" onClick={onOpenAll}>+{pending.length - SHOW} more waiting — open Leave requests →</button>
        )}
      </>)}
    </section>

    {deciding && <DecideDialog me={me} req={deciding.req} approve={deciding.approve} balanceOf={balanceOf} onClose={() => setDeciding(null)}
                               onDone={(m) => { setDeciding(null); flash(m); reload(); }} />}
    {quotas && <QuotaDialog me={me} onClose={() => setQuotas(false)} onDone={(m) => { setQuotas(false); flash(m); }} />}
  </>);
}

/**
 * Manager/director "Leave" tab. requests are the reportees' (manager) or
 * everyone's (director); the parent loads them so the nav can show a count.
 */
export function OpsLeave({ me, isDirector, requests, balanceOf, reload }) {
  const [deciding, setDeciding] = useState(null); // { req, approve }
  const [quotas, setQuotas] = useState(false);
  const [status, setStatus] = useState('');
  const [q, setQ] = useState('');
  const [notice, setNotice] = useState(null);
  const [error, setError] = useState(null);

  const flash = (m) => { setNotice(m); setTimeout(() => setNotice(null), 5000); };
  const today = todayStr();
  const pending = requests.filter((r) => r.status === 'pending')
    .sort((a, b) => a.from.localeCompare(b.from));
  const away = onLeave(requests, today).filter((r) => r.status === 'approved' && !isSunday(today));
  const upcoming = requests.filter((r) => r.status === 'approved' && r.from > today);
  const shown = requests.filter((r) => (!status || r.status === status) &&
    (!q || `${r.name} ${r.email} ${r.reason}`.toLowerCase().includes(q.toLowerCase())));

  const cancel = async (r) => {
    if (!window.confirm(`Cancel ${r.name}'s ${r.status} leave for ${range(r)}?`)) return;
    try { await call('POST', `/api/leave/${r.id}/cancel`, {}, me.token); flash('Leave cancelled'); reload(); }
    catch (e) { setError(e.message); }
  };

  return (<>
    <Toasts notice={notice} error={error} onClose={() => setError(null)} />
    <div className="stats">
      <Stat label="Awaiting decision" value={pending.length} accent={pending.length ? 'saffron' : ''} icon="clock"
            foot={pending.length ? `oldest from ${fmtShort(pending[0].from)}` : 'all caught up'} />
      <Stat label="On leave today" value={new Set(away.map((r) => r.accountId)).size} accent="cyan" icon="calendar"
            foot={away.length ? away.map((r) => r.name.split(' ')[0]).join(', ') : 'everyone in'} />
      <Stat label="Upcoming approved" value={upcoming.length} icon="log" foot="starting after today" />
      {isDirector
        ? <Stat label="Leave quotas" value="Edit" icon="edit"
                foot={<button className="link" onClick={() => setQuotas(true)}>Set yearly days →</button>} />
        : <Stat label="Requests" value={requests.length} icon="team" foot="from your reportees" />}
    </div>

    <section className="panel">
      <div className="panel-head"><h3>Awaiting your decision</h3><span className="muted">{pending.length} pending</span></div>
      {pending.length === 0 ? <Empty title="No pending requests" hint="New leave requests from your team will appear here." /> : (
        <div className="leave-cards">
          {pending.map((r) => (
            <div key={r.id} className="leave-card">
              <div className="who"><Avatar label={r.name} /><span>{r.name}<small>applied {fmtStamp(r.appliedAt)}</small></span></div>
              <div className="leave-what"><span className={`cat-tag ${r.category}`}>{CATS[r.category].label}</span><b>{range(r)}</b><span className="muted">{fmtAmount(r)}</span></div>
              <p title={r.reason}>{r.reason}</p>
              <BalanceChips balanceOf={balanceOf} r={r} />
              {r.accountId === me.id ? <span className="dim">Your own request — another approver decides it.</span> : (
                <div className="leave-act">
                  <button className="btn danger small" onClick={() => setDeciding({ req: r, approve: false })}>Reject</button>
                  <button className="btn primary small" onClick={() => setDeciding({ req: r, approve: true })}><Icon name="approve" size={15} />Approve</button>
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </section>

    <section className="panel">
      <div className="toolbar">
        <label className="search"><Icon name="search" /><input placeholder="Search name or reason…" value={q} onChange={(e) => setQ(e.target.value)} /></label>
        <select className="date" value={status} onChange={(e) => setStatus(e.target.value)} style={{ width: 'auto' }}>
          <option value="">All statuses</option>
          {Object.keys(STATUS_PILL).map((s) => <option key={s} value={s}>{s[0].toUpperCase() + s.slice(1)}</option>)}
        </select>
        <span className="muted push">{shown.length} request(s)</span>
      </div>
      <LeaveTable requests={shown} showName empty={requests.length ? 'No matches' : 'No leave requests yet'}
                  actions={(r) => (r.status === 'approved' || r.status === 'pending') && r.accountId !== me.id && (
                    <button className="btn ghost small" onClick={() => cancel(r)}>Cancel</button>
                  )} />
    </section>

    {deciding && <DecideDialog me={me} req={deciding.req} approve={deciding.approve} balanceOf={balanceOf} onClose={() => setDeciding(null)}
                               onDone={(m) => { setDeciding(null); flash(m); reload(); }} />}
    {quotas && <QuotaDialog me={me} onClose={() => setQuotas(false)} onDone={(m) => { setQuotas(false); flash(m); }} />}
  </>);
}
