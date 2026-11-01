import { Navigate, Outlet, useLocation } from 'react-router-dom';
import type { Role } from '../api/types';
import { homePathFor, useAuth } from './AuthContext';

interface Props {
  roles?: Role[];
}

/** Route guard: requires sign-in, enforces the first-login password change, and checks roles. */
export function RequireAuth({ roles }: Props) {
  const { session } = useAuth();
  const location = useLocation();

  if (!session) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  if (session.mustChangePassword && location.pathname !== '/change-password') {
    return <Navigate to="/change-password" replace />;
  }
  if (roles && !roles.includes(session.role)) {
    return <Navigate to={homePathFor(session)} replace />;
  }
  return <Outlet />;
}
