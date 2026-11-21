/* Shared visual pieces: brand mark, icons, small data helpers. */

export function DroneMark({ size = 36, spin = true }) {
  const rotor = (cx, cy) => (
    <g transform={`translate(${cx} ${cy})`}>
      <circle r="9" className="rotor-ring" />
      <g className={spin ? 'rotor spin' : 'rotor'}>
        <rect x="-8" y="-1.2" width="16" height="2.4" rx="1.2" />
      </g>
    </g>
  );
  return (
    <svg className="drone-mark" width={size} height={size} viewBox="0 0 64 64" aria-hidden="true">
      <path d="M14 14 L50 50 M50 14 L14 50" className="arm" />
      {rotor(14, 14)}{rotor(50, 14)}{rotor(14, 50)}{rotor(50, 50)}
      <rect x="24" y="24" width="16" height="16" rx="5" className="body" />
      <circle cx="32" cy="32" r="3" className="eye" />
    </svg>
  );
}

const paths = {
  radar: <><circle cx="12" cy="12" r="9" /><circle cx="12" cy="12" r="5" /><path d="M12 12 L19 5" /></>,
  log: <><path d="M5 4h11l3 3v13H5z" /><path d="M9 10h7M9 14h7M9 18h4" /></>,
  approve: <><path d="M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z" /><path d="M9 12l2 2 4-4" /></>,
  crew: <><circle cx="9" cy="9" r="3.5" /><path d="M3 20c0-3.3 2.7-6 6-6s6 2.7 6 6" /><circle cx="17" cy="8" r="2.5" /><path d="M16 14c2.8 0 5 2.2 5 5" /></>,
  sync: <><path d="M4 12a8 8 0 0 1 14-5.3L20 9" /><path d="M20 4v5h-5" /><path d="M20 12a8 8 0 0 1-14 5.3L4 15" /><path d="M4 20v-5h5" /></>,
  logout: <><path d="M15 4h4v16h-4" /><path d="M10 8l-4 4 4 4M6 12h10" /></>,
  search: <><circle cx="11" cy="11" r="6.5" /><path d="M16 16l4 4" /></>,
  clock: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
  plane: <><path d="M3 13l18-7-7 18-2.5-7.5z" /></>,
  bolt: <><path d="M13 3L5 14h6l-1 7 8-11h-6z" /></>,
  user: <><circle cx="12" cy="8" r="4" /><path d="M4 21c0-4.4 3.6-8 8-8s8 3.6 8 8" /></>,
  key: <><circle cx="8" cy="15" r="4" /><path d="M11 12l9-9M17 6l3 3M14 9l2 2" /></>,
  plus: <><path d="M12 5v14M5 12h14" /></>,
  edit: <><path d="M4 20h4L19 9l-4-4L4 16z" /><path d="M13 7l4 4" /></>,
  star: <><path d="M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1-4.4-4.3 6.1-.9z" /></>,
  team: <><circle cx="12" cy="7" r="3" /><circle cx="5" cy="10" r="2.2" /><circle cx="19" cy="10" r="2.2" /><path d="M7 20c0-2.8 2.2-5 5-5s5 2.2 5 5M1.5 19c0-2 1.6-3.5 3.5-3.5M22.5 19c0-2-1.6-3.5-3.5-3.5" /></>,
};

export function Icon({ name, size = 18 }) {
  return (
    <svg className="icon" width={size} height={size} viewBox="0 0 24 24" fill="none"
         stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {paths[name]}
    </svg>
  );
}

export function Avatar({ label }) {
  const text = (label || '?').replace(/@.*/, '');
  const parts = text.split(/[\s._-]+/).filter(Boolean);
  const ini = ((parts[0]?.[0] || '?') + (parts[1]?.[0] || '')).toUpperCase();
  let h = 0;
  for (const c of text) h = (h * 31 + c.charCodeAt(0)) % 360;
  return <span className="avatar" style={{ '--hue': h }}>{ini}</span>;
}

/* -------------------------------------------------------------- helpers */

export const todayStr = () => new Date().toLocaleDateString('sv'); // yyyy-MM-dd
export const hhmm = (t) => t.slice(11, 16);
export const dayOf = (t) => t.slice(0, 10);
const toDate = (t) => new Date(t.replace(' ', 'T'));

export function minutesBetween(a, b) {
  return Math.max(0, Math.round((toDate(b) - toDate(a)) / 60000));
}

export function fmtDuration(min) {
  if (!min) return '0h 00m';
  return `${Math.floor(min / 60)}h ${String(min % 60).padStart(2, '0')}m`;
}

/** Punches arrive newest-first; returns { 'yyyy-MM-dd': [oldest..newest] }. */
export function groupByDay(punches) {
  const out = {};
  for (const p of punches) (out[dayOf(p.time)] ||= []).unshift(p);
  return out;
}

/** Per-employee summary for a set of same-day punches. */
export function roster(punches) {
  const by = {};
  for (const p of punches) (by[p.enrollNumber] ||= []).unshift(p);
  return Object.values(by).map((list) => {
    const first = list[0], last = list[list.length - 1];
    return {
      enrollNumber: first.enrollNumber,
      name: first.name || `Crew #${first.enrollNumber}`,
      first: first.time, last: last.time, count: list.length,
      onBase: list.length % 2 === 1,
      minutes: minutesBetween(first.time, last.time),
    };
  }).sort((a, b) => b.last.localeCompare(a.last));
}

export function greeting() {
  const h = new Date().getHours();
  return h < 5 ? 'Night watch' : h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening';
}
