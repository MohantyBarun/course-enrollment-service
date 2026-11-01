import { useEffect, useState } from 'react';
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { onSlowRequestsChange } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { roleLabel } from '../lib/format';
import { Spinner } from './ui';

export function WakeUpNotice() {
  const [slow, setSlow] = useState(0);
  useEffect(() => onSlowRequestsChange(setSlow), []);
  if (!slow) return null;
  return (
    <div role="status" className="flex items-center justify-center gap-2 bg-amber-50 px-4 py-2 text-sm text-amber-800">
      <Spinner />
      The server is waking up (free hosting sleeps when idle). This can take up to a minute…
    </div>
  );
}

export function Brand() {
  return (
    <span className="flex items-center gap-2 font-semibold text-slate-900">
      <img src="/favicon.svg" alt="" className="h-7 w-7" />
      Student<span className="text-indigo-600">Hub</span>
    </span>
  );
}

/** Page shell for signed-in users. */
export function Layout() {
  const { session, isAdmin, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = async () => {
    await logout();
    navigate('/login', { replace: true });
  };

  const navClass = ({ isActive }: { isActive: boolean }) =>
    `rounded-md px-3 py-1.5 text-sm font-medium ${isActive ? 'bg-indigo-50 text-indigo-700' : 'text-slate-600 hover:text-slate-900'}`;

  return (
    <div className="flex min-h-screen flex-col">
      <WakeUpNotice />
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-3 px-4 py-3">
          <div className="flex items-center gap-6">
            <Link to="/"><Brand /></Link>
            {session && !session.mustChangePassword && (
              <nav className="flex flex-wrap gap-1" aria-label="Main">
                <NavLink to="/dashboard" className={navClass}>Dashboard</NavLink>
                {isAdmin && <NavLink to="/students" className={navClass}>Students</NavLink>}
                <NavLink to="/courses" className={navClass}>{isAdmin ? 'Courses' : 'Catalog'}</NavLink>
                {!isAdmin && <NavLink to="/my-courses" className={navClass}>My courses</NavLink>}
                <NavLink to="/announcements" className={navClass}>Announcements</NavLink>
                {!isAdmin && <NavLink to="/profile" className={navClass}>Profile</NavLink>}
                {isAdmin && <NavLink to="/emails" className={navClass}>Emails</NavLink>}
                {isAdmin && <NavLink to="/system" className={navClass}>System</NavLink>}
              </nav>
            )}
          </div>
          {session && (
            <div className="flex items-center gap-3">
              <div className="hidden text-right sm:block">
                <p className="text-sm font-medium text-slate-900">{session.email}</p>
                <p className="text-xs text-slate-500">{roleLabel(session.role)}</p>
              </div>
              {!session.mustChangePassword && (
                <Link to="/change-password" className="hidden text-xs text-slate-500 hover:text-slate-900 md:inline">Change password</Link>
              )}
              <button type="button" onClick={handleLogout} className="btn-secondary">Sign out</button>
            </div>
          )}
        </div>
      </header>
      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-8">
        <Outlet />
      </main>
      <footer className="py-6 text-center text-xs text-slate-400">
        Spring Boot · React · PostgreSQL —{' '}
        <a className="underline hover:text-slate-600" href="https://github.com/MohantyBarun/student-management-system"
           target="_blank" rel="noreferrer">source on GitHub</a>
      </footer>
    </div>
  );
}
