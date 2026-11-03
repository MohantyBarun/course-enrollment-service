import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { SlowServerNotice } from './Layout';
import { Logo } from './Logo';

const LINKS: [string, string, string][] = [
  ['/admin', 'Dashboard', '📊'],
  ['/admin/bookings', 'Bookings', '🎟️'],
  ['/admin/movies', 'Movies', '🎬'],
  ['/admin/cinemas', 'Cinemas', '🏛️'],
  ['/admin/shows', 'Shows', '🗓️'],
  ['/admin/emails', 'Emails', '✉️'],
  ['/admin/system', 'System', '🩺'],
];

export function AdminLayout() {
  const { session, logout } = useAuth();
  const navigate = useNavigate();
  const link = ({ isActive }: { isActive: boolean }) =>
    `flex shrink-0 items-center gap-2.5 rounded-xl px-3 py-2 text-sm font-medium transition
     ${isActive ? 'bg-marquee text-ink-950 shadow-glow' : 'text-ink-300 hover:bg-white/5 hover:text-white'}`;

  return (
    <div className="min-h-screen bg-ink-950 bg-aurora bg-fixed">
      <SlowServerNotice />
      <div className="mx-auto flex max-w-7xl flex-col gap-6 px-4 py-6 sm:px-6 lg:flex-row">
        <aside className="lg:sticky lg:top-6 lg:h-[calc(100vh-3rem)] lg:w-56 lg:shrink-0">
          <div className="card flex h-full flex-col gap-4 p-4">
            <div className="flex items-center justify-between">
              <Logo to="/admin" />
              <span className="chip">Admin</span>
            </div>
            <nav className="scrollbar-none -mx-1 flex gap-1 overflow-x-auto px-1 lg:flex-col" aria-label="Admin">
              {LINKS.map(([to, label, icon]) => (
                <NavLink key={to} to={to} end={to === '/admin'} className={link}>
                  <span aria-hidden="true">{icon}</span>{label}
                </NavLink>
              ))}
            </nav>
            <div className="mt-auto hidden space-y-2 border-t border-white/5 pt-4 text-xs lg:block">
              <p className="truncate text-ink-400">{session?.email}</p>
              <NavLink to="/" className="block text-ink-300 hover:text-white">← Customer site</NavLink>
              <button type="button" className="text-flame-300 hover:text-flame-400"
                      onClick={async () => {
                        await logout();
                        navigate('/');
                      }}>
                Sign out
              </button>
            </div>
          </div>
        </aside>
        <main className="min-w-0 flex-1 pb-10">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
