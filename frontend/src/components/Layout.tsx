import { useEffect, useRef, useState } from 'react';
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { onSlowRequestsChange } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { useCity } from '../hooks/useCity';
import { CityPicker } from './CityPicker';
import { Logo } from './Logo';

export function SlowServerNotice() {
  const [slow, setSlow] = useState(0);
  useEffect(() => onSlowRequestsChange(setSlow), []);
  if (!slow) return null;
  return (
    <div role="status" className="bg-sun-400/15 px-4 py-2 text-center text-xs font-medium text-sun-300">
      Waking the server up: the free hosting tier sleeps when idle, so this first request can take up to a minute.
    </div>
  );
}

function UserMenu() {
  const { session, isAdmin, logout } = useAuth();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => !ref.current?.contains(e.target as Node) && setOpen(false);
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [open]);

  if (!session) {
    return <Link to="/login" className="btn-primary px-4 py-2">Sign in</Link>;
  }
  const initial = session.name.trim().charAt(0).toUpperCase() || '?';
  const item = 'block rounded-lg px-3 py-2 text-sm text-ink-200 hover:bg-white/5 hover:text-white';
  return (
    <div className="relative" ref={ref}>
      <button type="button" onClick={() => setOpen((o) => !o)} aria-expanded={open} aria-haspopup="menu"
              className="flex items-center gap-2 rounded-full border border-white/10 bg-ink-800 py-1 pl-1 pr-3 text-sm hover:border-white/20">
        <span className="grid h-7 w-7 place-items-center rounded-full bg-marquee text-xs font-extrabold text-ink-950">{initial}</span>
        <span className="hidden max-w-[8rem] truncate font-medium sm:inline">{session.name}</span>
      </button>
      {open && (
        <div role="menu" className="card absolute right-0 z-40 mt-2 w-56 bg-ink-900 p-2 animate-fade-up" onClick={() => setOpen(false)}>
          <p className="truncate px-3 pb-2 pt-1 text-xs text-ink-400">{session.email}</p>
          {isAdmin ? (
            <Link role="menuitem" to="/admin" className={item}>Admin console</Link>
          ) : (
            <>
              <Link role="menuitem" to="/bookings" className={item}>My bookings</Link>
              <Link role="menuitem" to="/waitlist" className={item}>My waitlist</Link>
            </>
          )}
          <Link role="menuitem" to="/account" className={item}>Account</Link>
          <button role="menuitem" type="button" className={`${item} w-full text-left text-flame-300`}
                  onClick={async () => {
                    await logout();
                    navigate('/');
                  }}>
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}

export function Layout() {
  const { session } = useAuth();
  const { city, setPickerOpen } = useCity();
  const link = ({ isActive }: { isActive: boolean }) =>
    `rounded-full px-3 py-1.5 text-sm font-medium transition ${isActive ? 'bg-white/10 text-white' : 'text-ink-300 hover:text-white'}`;

  return (
    <div className="flex min-h-screen flex-col">
      <SlowServerNotice />
      <header className="sticky top-0 z-30 border-b border-white/5 bg-ink-950/80 backdrop-blur-xl">
        <div className="mx-auto flex h-16 max-w-7xl items-center gap-3 px-4 sm:px-6">
          <Logo />
          <button type="button" onClick={() => setPickerOpen(true)}
                  className="ml-1 flex items-center gap-1.5 rounded-full px-3 py-1.5 text-sm font-medium text-ink-200 hover:bg-white/5 hover:text-white"
                  aria-label={city ? `City: ${city.name}. Change city` : 'Choose your city'}>
            <span aria-hidden="true">📍</span>
            <span className="max-w-[7rem] truncate">{city?.name ?? 'Choose city'}</span>
            <span aria-hidden="true" className="text-ink-400">▾</span>
          </button>
          <nav className="ml-auto flex items-center gap-1" aria-label="Main">
            <NavLink to="/" end className={link}>Movies</NavLink>
            {session?.role === 'ROLE_CUSTOMER' && (
              <NavLink to="/bookings" className={(p) => `${link(p)} hidden sm:inline-flex`}>My bookings</NavLink>
            )}
            {session?.role === 'ROLE_ADMIN' && (
              <NavLink to="/admin" className={(p) => `${link(p)} hidden sm:inline-flex`}>Admin</NavLink>
            )}
          </nav>
          <UserMenu />
        </div>
      </header>

      <main className="flex-1">
        <Outlet />
      </main>

      <footer className="border-t border-white/5 py-8 text-center text-xs text-ink-500">
        <p>
          SeatSure is a portfolio project. Payments go through a mock gateway, so no real money moves.
        </p>
      </footer>
      <CityPicker />
    </div>
  );
}
