import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { LoginPage } from '../pages/LoginPage';
import { fakeJwt, jsonResponse, mockFetch, renderAt, signIn } from '../test/helpers';
import { RequireAuth } from './RequireAuth';

const guardedRoutes = (
  <>
    <Route element={<RequireAuth />}>
      <Route path="/change-password" element={<p>CHANGE PASSWORD PAGE</p>} />
      <Route path="/dashboard" element={<p>DASHBOARD PAGE</p>} />
      <Route element={<RequireAuth roles={['ROLE_ADMIN']} />}>
        <Route path="/students" element={<p>STUDENTS PAGE</p>} />
      </Route>
      <Route element={<RequireAuth roles={['ROLE_STUDENT']} />}>
        <Route path="/profile" element={<p>PROFILE PAGE</p>} />
      </Route>
    </Route>
  </>
);

describe('RequireAuth', () => {
  it('sends signed-out users to the login page', () => {
    renderAt('/students', guardedRoutes);
    expect(screen.getByText('LOGIN PAGE')).toBeInTheDocument();
  });

  it('forces users on a temporary password to change it first', () => {
    signIn({ role: 'ROLE_STUDENT', pwdChangeRequired: true });
    renderAt('/profile', guardedRoutes);
    expect(screen.getByText('CHANGE PASSWORD PAGE')).toBeInTheDocument();
  });

  it('redirects users away from pages for another role', () => {
    signIn({ role: 'ROLE_STUDENT' });
    renderAt('/students', guardedRoutes);
    expect(screen.getByText('DASHBOARD PAGE')).toBeInTheDocument();
  });

  it('lets users with the right role through', () => {
    signIn({ role: 'ROLE_ADMIN' });
    renderAt('/students', guardedRoutes);
    expect(screen.getByText('STUDENTS PAGE')).toBeInTheDocument();
  });
});

describe('LoginPage', () => {
  const routes = (
    <>
      {/* renderAt adds its own /login marker; override it with the real page */}
      <Route path="/signin" element={<LoginPage />} />
      {guardedRoutes}
    </>
  );

  it('shows a generic error for unknown accounts (no account enumeration)', async () => {
    mockFetch(() => jsonResponse({ error: 'No account found with email: x@x.com' }, 404));
    renderAt('/signin', routes);

    await userEvent.type(screen.getByLabelText(/email/i), 'x@x.com');
    await userEvent.type(screen.getByLabelText(/password/i), 'whatever');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password.');
    expect(screen.queryByText(/x@x.com/)).not.toBeInTheDocument();
  });

  it('shows lockout messages from the server', async () => {
    mockFetch(() => jsonResponse({ error: 'Account temporarily blocked. Try again after: …' }, 403));
    renderAt('/signin', routes);

    await userEvent.type(screen.getByLabelText(/email/i), 'a@x.com');
    await userEvent.type(screen.getByLabelText(/password/i), 'wrong');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent('temporarily blocked');
  });

  it('routes an admin to the dashboard after signing in', async () => {
    mockFetch(() => jsonResponse({
      accessToken: fakeJwt({ role: 'ROLE_ADMIN' }), refreshToken: 'r', tokenType: 'Bearer',
      role: 'ROLE_ADMIN', email: 'admin@x.com', isFirstTimeLogin: false,
    }));
    renderAt('/signin', routes);

    await userEvent.type(screen.getByLabelText(/email/i), 'admin@x.com');
    await userEvent.type(screen.getByLabelText(/password/i), 'Admin@123');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => expect(screen.getByText('DASHBOARD PAGE')).toBeInTheDocument());
    expect(localStorage.getItem('sms.refreshToken')).toBe('r');
  });

  it('routes a first-time student to the password change page', async () => {
    mockFetch(() => jsonResponse({
      accessToken: fakeJwt({ role: 'ROLE_STUDENT', pwdChangeRequired: true }), refreshToken: 'r',
      tokenType: 'Bearer', role: 'ROLE_STUDENT', email: 's@x.com', isFirstTimeLogin: true,
    }));
    renderAt('/signin', routes);

    await userEvent.type(screen.getByLabelText(/email/i), 's@x.com');
    await userEvent.type(screen.getByLabelText(/password/i), 'Temp#123');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => expect(screen.getByText('CHANGE PASSWORD PAGE')).toBeInTheDocument());
  });
});
