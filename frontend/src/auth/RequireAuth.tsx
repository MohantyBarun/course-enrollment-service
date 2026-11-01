import { Navigate, Outlet, useLocation } from 'react-router-dom';
import type { Role } from '../api/types';
import { homePathFor, useAuth } from './AuthContext';

/** Route guard: requires sign-in (returning here afterwards) and, optionally, a role. */
export function RequireAuth({ role }: { role?: Role }) {
  const { session } = useAuth();
  const location = useLocation();

  if (!session) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  if (role && session.role !== role) {
    return <Navigate to={homePathFor(session)} replace />;
  }
  return <Outlet />;
}
