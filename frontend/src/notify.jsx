/* Notifications bell — everything waiting on you in the role you're viewing as. */

import { useCallback, useEffect, useRef, useState } from 'react';
import { Icon, call } from './ui.jsx';

const ago = (ms) => {
  const s = Math.max(0, (Date.now() - ms) / 1000);
  return s < 3600 ? `${Math.max(1, Math.round(s / 60))}m ago` : s < 86400 ? `${Math.round(s / 3600)}h ago` : `${Math.round(s / 86400)}d ago`;
};
const ICON = {
  leave: 'calendar', 'leave-decided': 'calendar', fix: 'clock', 'fix-decided': 'clock', 'missed-punch': 'clock',
  recommendation: 'money', salary: 'money', mbo: 'chat',
};

export function NotificationBell({ me, role, onGo }) {
  const [data, setData] = useState({ count: 0, items: [] });
  const [open, setOpen] = useState(false);
  const box = useRef(null);
  const load = useCallback(async () => {
    try { setData(await call('GET', `/api/notifications?as=${role}`, null, me.token)); }
    catch { /* the bell stays quiet on errors; each tab shows its own */ }
  }, [me.token, role]);
  useEffect(() => {
    load();
    const t = setInterval(load, 30000);
    return () => clearInterval(t);
  }, [load]);
  useEffect(() => {
    if (!open) return undefined;
    const close = (e) => { if (box.current && !box.current.contains(e.target)) setOpen(false); };
    const esc = (e) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', close);
    window.addEventListener('keydown', esc);
    return () => { document.removeEventListener('mousedown', close); window.removeEventListener('keydown', esc); };
  }, [open]);

  return (
    <div className="bell" ref={box}>
      <button className={`icon-btn neutral ${data.count ? 'has' : ''}`} title="Notifications"
              onClick={() => { setOpen(!open); if (!open) load(); }} aria-expanded={open}>
        <Icon name="bell" />
        {data.count > 0 && <em className="bell-count">{data.count > 99 ? '99+' : data.count}</em>}
      </button>
      {open && (
        <div className="bell-panel" role="dialog" aria-label="Notifications">
          <div className="bell-head"><b>Waiting on you</b><span className="muted">{data.count || 'nothing'}</span></div>
          {!data.items.length ? <p className="dim bell-empty">You're all caught up.</p> : (
            <ul>
              {data.items.slice(0, 30).map((n) => (
                <li key={`${n.kind}${n.id}`}>
                  <button onClick={() => { setOpen(false); onGo(n.tab, n.as); }}>
                    <Icon name={ICON[n.kind] || 'bolt'} size={16} />
                    <span><b>{n.title}</b><small>{n.detail}</small></span>
                    <time>{ago(n.at)}</time>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
