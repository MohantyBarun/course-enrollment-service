import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { onSessionExpired } from '../api/client';
import { authApi } from '../api/endpoints';
import type { Role, TokenResponse } from '../api/types';
import { decodeJwt } from '../lib/jwt';
import { tokenStore } from '../lib/tokens';

export interface Session {
  email: string;
  name: string;
  role: Role;
}

interface AuthContextValue {
  session: Session | null;
  isAdmin: boolean;
  login: (email: string, password: string) => Promise<Session>;
  register: (form: { name: string; email: string; phone: string; password: string }) => Promise<Session>;
  /** Passwordless sign-in to the demo accounts (when the backend has demo mode on). */
  loginAsDemo: (role: 'ADMIN' | 'CUSTOMER') => Promise<Session>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);
const NAME_KEY = 'seatsure.name';

function storedSession(): Session | null {
  const tokens = tokenStore.get();
  if (!tokens) return null;
  const claims = decodeJwt(tokens.accessToken);
  if (!claims?.sub || !claims.role) return null;
  return { email: claims.sub, role: claims.role, name: localStorage.getItem(NAME_KEY) ?? claims.sub.split('@')[0] };
}

export function AuthProvider({ children }: { children: ReactNode }) {
  // The access token may be expired; the API client refreshes it when it's next needed.
  const [session, setSession] = useState<Session | null>(storedSession);

  useEffect(() => onSessionExpired(() => setSession(null)), []);

  const start = useCallback((response: TokenResponse) => {
    tokenStore.set({ accessToken: response.accessToken, refreshToken: response.refreshToken });
    localStorage.setItem(NAME_KEY, response.user.name);
    const next: Session = { email: response.user.email, name: response.user.name, role: response.user.role };
    setSession(next);
    return next;
  }, []);

  const login = useCallback(async (email: string, password: string) => start(await authApi.login(email, password)), [start]);
  const register = useCallback(
    async (form: { name: string; email: string; phone: string; password: string }) => start(await authApi.register(form)),
    [start]);
  const loginAsDemo = useCallback(async (role: 'ADMIN' | 'CUSTOMER') => start(await authApi.demoLogin(role)), [start]);

  const logout = useCallback(async () => {
    const tokens = tokenStore.get();
    tokenStore.clear();
    localStorage.removeItem(NAME_KEY);
    setSession(null);
    if (tokens) {
      // Revoke server-side; the local sign-out already happened, so ignore failures.
      await authApi.logout(tokens.refreshToken).catch(() => undefined);
    }
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ session, isAdmin: session?.role === 'ROLE_ADMIN', login, register, loginAsDemo, logout }),
    [session, login, register, loginAsDemo, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}

/** Where a signed-in user lands by default. */
export function homePathFor(session: Session): string {
  return session.role === 'ROLE_ADMIN' ? '/admin' : '/';
}
