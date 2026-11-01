import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { CityProvider } from '../hooks/useCity';

const b64url = (obj: object) =>
  btoa(JSON.stringify(obj)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

/** Builds an unsigned JWT with the same claims as the backend (signature isn't checked client-side). */
export function fakeJwt(claims: Record<string, unknown> = {}, expiresInSeconds = 900): string {
  const now = Math.floor(Date.now() / 1000);
  return [
    b64url({ alg: 'HS512' }),
    b64url({ sub: 'customer@x.com', uid: 7, role: 'ROLE_CUSTOMER', iat: now, exp: now + expiresInSeconds, ...claims }),
    'signature',
  ].join('.');
}

export function signInAsAdmin() {
  signIn({ sub: 'admin@x.com', role: 'ROLE_ADMIN' });
}

export function signIn(claims: Record<string, unknown> = {}, expiresInSeconds = 900) {
  localStorage.setItem('seatsure.accessToken', fakeJwt(claims, expiresInSeconds));
  localStorage.setItem('seatsure.refreshToken', 'refresh-token');
}

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

/** Stubs global fetch with a handler; returns the mock for assertions. */
export function mockFetch(handler: (url: URL, init: RequestInit) => Response | Promise<Response>) {
  const fn = vi.fn((input: RequestInfo | URL, init: RequestInit = {}) =>
    Promise.resolve(handler(new URL(String(input)), init)));
  vi.stubGlobal('fetch', fn);
  return fn;
}

/** Renders a route tree at `path`, with a marker element for every destination we might redirect to. */
export function renderAt(path: string, routes: ReactElement) {
  return render(
    <MemoryRouter initialEntries={[path]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <AuthProvider>
        <CityProvider>
          <Routes>
            {routes}
            <Route path="/login" element={<p>LOGIN PAGE</p>} />
          </Routes>
        </CityProvider>
      </AuthProvider>
    </MemoryRouter>,
  );
}
