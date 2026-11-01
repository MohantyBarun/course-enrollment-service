import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { demoApi } from '../api/academics';
import { authApi } from '../api/auth';
import { forceRefresh, onSessionExpired } from '../api/client';
import type { LoginResponse, Role } from '../api/types';
import { decodeJwt } from '../lib/jwt';
import { tokenStore } from '../lib/tokens';

export interface Session {
  email: string;
  role: Role;
  /** True while the user is on a temporary password; the API only allows change-password. */
  mustChangePassword: boolean;
}

interface AuthContextValue {
  session: Session | null;
  isAdmin: boolean;
  login: (email: string, password: string) => Promise<Session>;
  /** Passwordless sign-in to the demo accounts (when the backend has demo mode on). */
  loginAsDemo: (role: 'ADMIN' | 'STUDENT') => Promise<Session>;
  logout: () => Promise<void>;
  changePassword: (oldPassword: string, newPassword: string, confirmNewPassword: string) => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function sessionFromToken(accessToken: string | undefined): Session | null {
  if (!accessToken) return null;
  const claims = decodeJwt(accessToken);
  if (!claims?.sub || !claims.role) return null;
  return { email: claims.sub, role: claims.role, mustChangePassword: Boolean(claims.pwdChangeRequired) };
}

export function AuthProvider({ children }: { children: ReactNode }) {
  // Restore from storage. The access token may be expired; the API client refreshes it when needed.
  const [session, setSession] = useState<Session | null>(() => sessionFromToken(tokenStore.get()?.accessToken));

  useEffect(() => onSessionExpired(() => setSession(null)), []);

  const start = useCallback((response: LoginResponse) => {
    tokenStore.set({ accessToken: response.accessToken, refreshToken: response.refreshToken });
    const next = sessionFromToken(response.accessToken)!;
    setSession(next);
    return next;
  }, []);

  const login = useCallback(
    async (email: string, password: string) => start(await authApi.login(email, password)), [start]);

  const loginAsDemo = useCallback(
    async (role: 'ADMIN' | 'STUDENT') => start(await demoApi.login(role)), [start]);

  const logout = useCallback(async () => {
    const tokens = tokenStore.get();
    tokenStore.clear();
    setSession(null);
    if (tokens) {
      // Revoke server-side; the local sign-out already happened, so ignore failures.
      await authApi.logout(tokens.refreshToken).catch(() => undefined);
    }
  }, []);

  const changePassword = useCallback(async (oldPassword: string, newPassword: string, confirm: string) => {
    await authApi.changePassword(oldPassword, newPassword, confirm);
    // The current access token still says "password change required"; swap it for a full one.
    const accessToken = await forceRefresh();
    setSession(sessionFromToken(accessToken));
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      session,
      isAdmin: session?.role === 'ROLE_ADMIN' || session?.role === 'ROLE_SUPER_ADMIN',
      login,
      loginAsDemo,
      logout,
      changePassword,
    }),
    [session, login, loginAsDemo, logout, changePassword],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}

/** Where a signed-in user belongs by default. */
export function homePathFor(session: Session): string {
  return session.mustChangePassword ? '/change-password' : '/dashboard';
}
