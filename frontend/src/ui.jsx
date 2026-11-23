/* Shared visual pieces: brand mark, icons, small data helpers. */

import { useEffect, useState } from 'react';

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
  calendar: <><rect x="3.5" y="5" width="17" height="15.5" rx="2.5" /><path d="M3.5 10h17M8 3v4M16 3v4" /><path d="M8 14h2M14 14h2M8 17.5h2" /></>,
  sun: <><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" /></>,
  moon: <><path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z" /></>,
  monitor: <><rect x="3" y="4" width="18" height="12" rx="2" /><path d="M8 20h8M12 16v4" /></>,
  chat: <><path d="M4 5h16v11H9l-5 4z" /><path d="M8 9h8M8 12.5h5" /></>,
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

/* ---------------------------------------------------------------- theme */

const THEME_KEY = 'vyom_theme';            // 'system' | 'light' | 'dark'
const THEME_NEXT = { system: 'light', light: 'dark', dark: 'system' };
const THEME_META = {
  system: { icon: 'monitor', label: 'System theme' },
  light: { icon: 'sun', label: 'Light theme' },
  dark: { icon: 'moon', label: 'Dark theme' },
};

function readTheme() {
  try { const t = localStorage.getItem(THEME_KEY); return THEME_META[t] ? t : 'system'; } catch { return 'system'; }
}

/** Resolves the preference to light/dark and stamps it on <html data-theme>. */
function applyTheme(pref) {
  const dark = pref === 'dark' || (pref === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', dark ? '#060b18' : '#dde4ef');
}

/** Cycles System → Light → Dark; remembered per browser. */
export function ThemeToggle({ className = '' }) {
  const [pref, setPref] = useState(readTheme);
  useEffect(() => {
    applyTheme(pref);
    try { localStorage.setItem(THEME_KEY, pref); } catch { /* storage unavailable */ }
    if (pref !== 'system') return undefined;
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = () => applyTheme('system');
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, [pref]);
  const { icon, label } = THEME_META[pref];
  const next = THEME_META[THEME_NEXT[pref]].label.toLowerCase();
  return (
    <button type="button" className={`icon-btn neutral ${className}`} title={`${label} — switch to ${next}`}
            aria-label={`${label}. Switch to ${next}`} onClick={() => setPref(THEME_NEXT[pref])}>
      <Icon name={icon} />
    </button>
  );
}

/* ------------------------------------------------------- shared widgets */

export async function call(method, url, body, token) {
  const headers = {};
  if (body) headers['Content-Type'] = 'application/json';
  if (token) headers['Authorization'] = `Bearer ${token}`;
  const res = await fetch(url, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `${method} ${url} failed (${res.status})`);
  return data;
}

export function Toasts({ notice, error, onClose }) {
  if (!notice && !error) return null;
  return (
    <div className="toasts">
      {notice && <div className="toast ok"><Icon name="bolt" />{notice}</div>}
      {error && <div className="toast err" onClick={onClose}>⚠ {error}<span className="toast-x">×</span></div>}
    </div>
  );
}

export function Empty({ title, hint }) {
  return (
    <div className="empty">
      <div className="empty-radar"><span /></div>
      <b>{title}</b>
      {hint && <p>{hint}</p>}
    </div>
  );
}

export function Stat({ label, value, unit, accent, icon, foot }) {
  return (
    <div className={`stat ${accent || ''}`}>
      <div className="stat-top"><span>{label}</span><Icon name={icon} /></div>
      <div className="stat-value">{value}{unit && <small>{unit}</small>}</div>
      {foot && <div className="stat-foot">{foot}</div>}
    </div>
  );
}

export function Modal({ title, eyebrow, onClose, children }) {
  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={title}>
        <div className="modal-head">
          <div>{eyebrow && <span className="eyebrow">{eyebrow}</span>}<h2>{title}</h2></div>
          <button className="icon-btn neutral" title="Close" onClick={onClose}>×</button>
        </div>
        {children}
      </div>
    </div>
  );
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
