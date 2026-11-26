/* My calendar — one month of the employee's own days: holidays, leave and attendance. */

import { useEffect, useMemo, useState } from 'react';
import { Modal, call, fmtDuration, hhmm, minutesBetween, todayStr } from './ui.jsx';
import { CATS, HALF, isShort, useHolidays } from './leave.jsx';

// Mirrors PayrollStore: a working day needs an 8:30 h first-to-last span (10 minutes of grace);
// approved leave lowers it (half-day: 4 h left; late/early of X h: 8:30 h - X h; full day: nothing).
const SHIFT_MIN = 8 * 60 + 30;
const HALF_DAY_REQUIRED_MIN = 4 * 60;
const GRACE_MIN = 10;
const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

const parse = (s) => new Date(`${s}T00:00:00Z`);
const iso = (d) => d.toISOString().slice(0, 10);
const addDays = (s, n) => { const d = parse(s); d.setUTCDate(d.getUTCDate() + n); return iso(d); };
const isSunday = (s) => parse(s).getUTCDay() === 0;
const fmtLong = (s) => parse(s).toLocaleDateString('en-GB', { day: '2-digit', month: 'long', year: 'numeric', timeZone: 'UTC' });
const fmtWeekday = (s) => parse(s).toLocaleDateString('en-GB', { weekday: 'long', timeZone: 'UTC' });
const hm = (min) => `${Math.floor(min / 60)}:${String(min % 60).padStart(2, '0')}`;
const num = (n) => Math.round(n * 100) / 100;

const KIND = {
  present: { label: 'Worked', pill: 'live' },
  short: { label: 'Short day', pill: 'warn' },
  missing: { label: 'Missing punch', pill: 'warn' },
  absent: { label: 'Absent', pill: 'bad' },
  leave: { label: 'On leave', pill: 'live' },
  holiday: { label: 'Holiday', pill: 'idle' },
  sunday: { label: 'Sunday', pill: 'idle' },
  today: { label: 'Today', pill: 'live' },
  future: { label: 'Upcoming', pill: 'idle' },
  none: { label: 'No attendance record', pill: 'idle' },
};

/** Minutes of the 8:30 h day one approved request covers (as PayrollStore.approvedLeave). */
function coveredMin(r) {
  if (!r.halfDay) return SHIFT_MIN;
  if (r.halfDay === 'first' || r.halfDay === 'second') return SHIFT_MIN - HALF_DAY_REQUIRED_MIN;
  return Math.round(r.hours * 60);
}

/** Leave days one request takes on one working day: 1, 0.5 or hours / 8. */
const leaveDays = (r) => (!r.halfDay ? 1 : isShort(r.halfDay) ? r.hours / 8 : 0.5);

const leaveText = (r) => `${CATS[r.category].label} leave${r.halfDay ? ` · ${HALF[r.halfDay]}${isShort(r.halfDay) ? ` ${r.hours} h` : ''}` : ''}`;

/** Everything the calendar knows about one day. since: the first day with any punch. */
function describe(d, { today, days, requests, holidays, linked, since }) {
  const list = days[d] || [];
  const covering = requests.filter((r) => r.from <= d && r.to >= d);
  const approved = covering.filter((r) => r.status === 'approved');
  const pending = covering.filter((r) => r.status === 'pending');
  const holiday = holidays[d];
  const span = list.length > 1 ? minutesBetween(list[0].time, list[list.length - 1].time) : 0;
  const required = Math.max(0, SHIFT_MIN - Math.min(SHIFT_MIN, approved.reduce((s, r) => s + coveredMin(r), 0)));
  const off = isSunday(d) || !!holiday;

  let kind;
  if (isSunday(d)) kind = 'sunday';
  else if (holiday) kind = 'holiday';
  else if (required === 0) kind = 'leave';
  else if (d > today) kind = 'future';
  else if (d === today) kind = 'today';
  else if (!linked || !since || d < since) kind = 'none';
  else if (!list.length) kind = 'absent';
  else if (list.length % 2 === 1) kind = 'missing';
  else kind = required - span > GRACE_MIN ? 'short' : 'present';

  return { d, kind, list, span, required, approved, pending, holiday, off, onBase: d === today && list.length % 2 === 1 };
}

/* -------------------------------------------------------------- calendar */

/**
 * The employee's month at a glance on "My dashboard". days: the employee's own
 * punches grouped by day (groupByDay), already loaded by the dashboard.
 */
export function WorkCalendar({ me, days, linked }) {
  const [month, setMonth] = useState(() => todayStr().slice(0, 7)); // yyyy-MM
  const [open, setOpen] = useState(null); // yyyy-MM-dd of the day dialog
  const [requests, setRequests] = useState([]);
  const holidays = useHolidays(me);
  const today = todayStr();

  useEffect(() => {
    call('GET', '/api/leave/my', null, me.token).then((d) => setRequests(d.requests || [])).catch(() => {});
  }, [me.token]);

  const since = useMemo(() => Object.keys(days).sort()[0] || null, [days]);
  const ctx = { today, days, requests, holidays, linked, since };

  const first = `${month}-01`;
  const lead = (parse(first).getUTCDay() + 6) % 7; // Monday-first grid
  const daysInMonth = new Date(Date.UTC(+month.slice(0, 4), +month.slice(5, 7), 0)).getUTCDate();
  const info = Array.from({ length: daysInMonth }, (_, i) => describe(addDays(first, i), ctx));
  const shift = (n) => { const d = parse(first); d.setUTCMonth(d.getUTCMonth() + n); setMonth(iso(d).slice(0, 7)); };
  const title = parse(first).toLocaleDateString('en-GB', { month: 'long', year: 'numeric', timeZone: 'UTC' });

  const count = (k) => info.filter((x) => x.kind === k).length;
  const sum = {
    working: info.filter((x) => !x.off).length,
    worked: info.filter((x) => x.list.length > 0 && x.kind !== 'today').length,
    leave: num(info.filter((x) => !x.off).reduce((s, x) => s + Math.min(1, x.approved.reduce((t, r) => t + leaveDays(r), 0)), 0)),
    holidays: count('holiday'),
    absent: count('absent'),
    flagged: count('short') + count('missing'),
    minutes: info.reduce((s, x) => s + (x.kind === 'today' ? 0 : x.span), 0),
  };
  // average over days with an in and an out; today (still running) and missing-punch days are left out
  const spanDays = info.filter((x) => x.span > 0 && x.kind !== 'today').length;
  sum.avg = spanDays ? Math.round(sum.minutes / spanDays) : 0;

  return (<>
    <section className="panel">
      <div className="panel-head">
        <h3>My calendar</h3>
        <span className="muted">Holidays, leave and attendance</span>
      </div>
      <div className="cal wcal">
        <div className="cal-head">
          <button className="icon-btn neutral" title="Previous month" onClick={() => shift(-1)}>‹</button>
          <div><b>{title}</b><span className="muted">{sum.working} working day{sum.working === 1 ? '' : 's'}</span></div>
          <button className="icon-btn neutral" title="Next month" onClick={() => shift(1)}>›</button>
          {month !== today.slice(0, 7) && <button className="btn ghost small" onClick={() => setMonth(today.slice(0, 7))}>Today</button>}
        </div>

        <div className="wcal-facts">
          {linked && <span><b>{sum.worked}</b>days worked</span>}
          {linked && <span><b>{fmtDuration(sum.minutes)}</b>on base</span>}
          {linked && <span title={`Average first-to-last span over ${spanDays} day(s) with an in and an out`}><b>{fmtDuration(sum.avg)}</b>avg / day</span>}
          <span><b>{sum.leave}</b>leave day{sum.leave === 1 ? '' : 's'}</span>
          <span><b>{sum.holidays}</b>holiday{sum.holidays === 1 ? '' : 's'}</span>
          {linked && <span className={sum.absent ? 'bad' : ''}><b>{sum.absent}</b>absent</span>}
          {linked && <span className={sum.flagged ? 'warn' : ''}><b>{sum.flagged}</b>short / missing punch</span>}
        </div>

        <div className="cal-grid">
          {WEEKDAYS.map((w) => <span key={w} className={`cal-wd ${w === 'Sun' ? 'sun' : ''}`}>{w}</span>)}
          {Array.from({ length: lead }, (_, i) => <span key={`x${i}`} />)}
          {info.map((x) => <DayCell key={x.d} x={x} isToday={x.d === today} onOpen={() => setOpen(x.d)} />)}
        </div>

        <div className="cal-legend">
          {linked && <span><i className="sw present" />Worked</span>}
          {linked && <span><i className="sw short" />Short / missing punch</span>}
          {linked && <span><i className="sw absent" />Absent</span>}
          <span><i className="sw personal approved" />Personal leave</span>
          <span><i className="sw sick approved" />Sick leave</span>
          <span><i className="sw personal pending" />Leave pending</span>
          <span><i className="sw holiday" />Holiday</span>
          <span><i className="sw sun" />Sunday</span>
        </div>
        {!linked && <p className="wcal-note muted">Attendance appears here once a director links your account to the attendance terminal.</p>}
      </div>
    </section>

    {open && <DayDialog x={describe(open, ctx)} onClose={() => setOpen(null)} />}
  </>);
}

function DayCell({ x, isToday, onOpen }) {
  const lv = x.approved[0] || x.pending[0];
  // approved wins over pending; approved leave that doesn't cover the whole day shows as a half cell
  const leaveCls = !x.off && lv && (x.approved.length
    ? `leave ${lv.category} approved${x.kind !== 'leave' ? ' half' : ''}`
    : `leave ${lv.category} pending`);
  const cls = ['cal-day', x.kind, isSunday(x.d) && 'sun', isToday && 'today', leaveCls].filter(Boolean).join(' ');
  const tip = [fmtLong(x.d), KIND[x.kind].label, x.holiday,
    ...x.approved.map(leaveText), ...x.pending.map((r) => `${leaveText(r)} (pending)`),
    x.list.length > 1 && `${hhmm(x.list[0].time)}–${hhmm(x.list[x.list.length - 1].time)}`].filter(Boolean).join('\n');

  let mark = null;
  if (x.holiday) mark = <em className="hol">{x.holiday}</em>;
  else if (x.kind === 'absent') mark = <em className="tag">Absent</em>;
  else if (x.kind === 'missing') mark = <em className="tag">1 punch</em>;
  else if (x.onBase) mark = <em className="tag">on base</em>;
  else if (x.span > 0) mark = <em className="tag">{hm(x.span)}</em>;

  return (
    <button type="button" className={cls} title={tip} onClick={onOpen}>
      <span>{+x.d.slice(8)}</span>
      <div className="wcal-foot">
        {!x.off && lv && <em className="lv">{CATS[lv.category].short}{lv.halfDay ? (isShort(lv.halfDay) ? ` ${lv.hours}h` : '½') : ''}{x.approved.length ? '' : '?'}</em>}
        {mark}
      </div>
    </button>
  );
}

/* ---------------------------------------------------------------- details */

function DayDialog({ x, onClose }) {
  const k = KIND[x.kind];
  const worked = x.list.length > 0;
  const shortBy = Math.max(0, x.required - x.span);
  const why = {
    present: `Met the ${hm(x.required)} h needed for the day.`,
    short: `${fmtDuration(shortBy)} short of the ${hm(x.required)} h needed for the day.`,
    missing: 'Only one punch for this day: the check-out (or check-in) is missing. Request a fix from Punch history.',
    absent: 'A working day with no punches and no approved leave.',
    leave: 'Covered by approved leave.',
    holiday: 'Company holiday, a paid day off.',
    sunday: 'Weekly off.',
    today: x.onBase ? 'You are on base right now.' : worked ? 'Signed out for now.' : 'No punch yet today.',
    future: 'Not counted yet.',
    none: 'No attendance is recorded for this day.',
  }[x.kind];

  return (
    <Modal title={fmtLong(x.d)} eyebrow={fmtWeekday(x.d)} onClose={onClose}>
      <div className="wcal-day">
        <div className="wcal-status"><span className={`pill ${k.pill}`}>{k.label}</span><span className="muted">{why}</span></div>

        {x.holiday && <div className="wcal-row"><span className="cat-tag holiday">Holiday</span><b>{x.holiday}</b></div>}

        {[...x.approved, ...x.pending].map((r) => (
          <div key={r.id} className="wcal-row">
            <span className={`cat-tag ${r.category}`}>{CATS[r.category].label}</span>
            <div>
              <b>{r.halfDay ? HALF[r.halfDay] : 'Full day'}{isShort(r.halfDay) ? ` · ${r.hours} h` : ''}</b>
              <span className={`pill ${r.status === 'approved' ? 'live' : 'warn'}`}>{r.status}</span>
              {r.reason && <small className="sub">“{r.reason}”</small>}
              {r.decidedBy && <small className="sub">approved by {r.decidedBy}{r.note ? ` — “${r.note}”` : ''}</small>}
            </div>
          </div>
        ))}

        {(worked || !x.off) && x.kind !== 'future' && x.kind !== 'none' && (
          <div className="duty-facts">
            <div><span>First in</span><b>{worked ? hhmm(x.list[0].time) : '--:--'}</b></div>
            <div><span>Last out</span><b>{x.list.length > 1 ? hhmm(x.list[x.list.length - 1].time) : '--:--'}</b></div>
            <div><span>On base</span><b>{hm(x.span)}</b></div>
            {!x.off && <div><span>Needed</span><b>{hm(x.required)}</b></div>}
          </div>
        )}

        {worked && (
          <ol className="timeline">
            {x.list.map((p, i) => (
              <li key={i} className={i % 2 === 0 ? 'in' : 'out'}>
                <b className="mono">{hhmm(p.time)}</b>
                <span>{i % 2 === 0 ? 'Checked in' : 'Checked out'}</span>
                <small className="dim">{p.addedBy ? `added by ${p.addedBy} on request` : p.verifyLabel || ''}</small>
              </li>
            ))}
          </ol>
        )}
      </div>
    </Modal>
  );
}
