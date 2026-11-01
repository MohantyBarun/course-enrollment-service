import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import type { Student } from '../api/types';
import { fakeJwt, jsonResponse, mockFetch, renderAt, signIn } from '../test/helpers';
import { ChangePasswordPage } from './ChangePasswordPage';
import { StudentsPage } from './StudentsPage';

const student = (id: number, name: string): Student => ({
  id, name, email: `${name.toLowerCase().replace(' ', '.')}@x.com`, department: 'Engineering', branch: 'CSE',
  dateOfBirth: '2003-05-14', presentAddress: null, permanentAddress: null,
  createdAt: '2026-10-01T10:00:00', updatedAt: '2026-10-01T10:00:00',
});

const page = (content: Student[], total = content.length) =>
  ({ content, page: 0, size: 10, totalElements: total, totalPages: Math.ceil(total / 10), last: total <= 10 });

describe('StudentsPage', () => {
  it('lists students from the API', async () => {
    signIn();
    mockFetch(() => jsonResponse(page([student(1, 'Alice Kumar'), student(2, 'Bob Das')])));
    renderAt('/students', <Route path="/students" element={<StudentsPage />} />);

    expect(await screen.findByText('Alice Kumar')).toBeInTheDocument();
    expect(screen.getByText('Bob Das')).toBeInTheDocument();
    expect(screen.getByText('2 students')).toBeInTheDocument();
  });

  it('sends the search term to the API (debounced)', async () => {
    signIn();
    const fetchMock = mockFetch(() => jsonResponse(page([])));
    renderAt('/students', <Route path="/students" element={<StudentsPage />} />);
    await screen.findByText('No students yet');

    await userEvent.type(screen.getByLabelText('Search students'), 'alice');

    await waitFor(() => {
      const urls = fetchMock.mock.calls.map(([u]) => new URL(String(u)));
      expect(urls.some((u) => u.searchParams.get('search') === 'alice')).toBe(true);
    });
  });

  it('shows the one-time temporary password after registering a student', async () => {
    signIn();
    mockFetch((url, init) => {
      if (url.pathname === '/students' && init.method === 'POST') {
        return jsonResponse({ ...student(3, 'New Student'), temporaryPassword: 'Xk9#mP2q' }, 201);
      }
      return jsonResponse(page([]));
    });
    renderAt('/students', <Route path="/students" element={<StudentsPage />} />);
    await screen.findByText('No students yet');

    await userEvent.click(screen.getByRole('button', { name: /register student/i }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText(/full name/i), 'New Student');
    await userEvent.type(within(dialog).getByLabelText(/email/i), 'new.student@x.com');
    await userEvent.type(within(dialog).getByLabelText(/department/i), 'Engineering');
    await userEvent.type(within(dialog).getByLabelText(/branch/i), 'CSE');
    await userEvent.type(within(dialog).getByLabelText(/date of birth/i), '2003-05-14');
    await userEvent.click(within(dialog).getByRole('button', { name: /register student/i }));

    expect(await screen.findByTestId('temp-password')).toHaveTextContent('Xk9#mP2q');
    expect(screen.getByText(/won’t be shown again/)).toBeInTheDocument();
  });

  it('validates the form before calling the API', async () => {
    signIn();
    const fetchMock = mockFetch(() => jsonResponse(page([])));
    renderAt('/students', <Route path="/students" element={<StudentsPage />} />);
    await screen.findByText('No students yet');

    await userEvent.click(screen.getByRole('button', { name: /register student/i }));
    const dialog = screen.getByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: /register student/i }));

    expect(within(dialog).getByText('Name must be 2–100 characters')).toBeInTheDocument();
    expect(within(dialog).getByText('Enter a valid email address')).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false);
  });

  it('maps a duplicate-email conflict onto the email field', async () => {
    signIn();
    mockFetch((url, init) => (url.pathname === '/students' && init.method === 'POST'
      ? jsonResponse({ error: "Student with 'a@x.com' already exists" }, 409)
      : jsonResponse(page([]))));
    renderAt('/students', <Route path="/students" element={<StudentsPage />} />);
    await screen.findByText('No students yet');

    await userEvent.click(screen.getByRole('button', { name: /register student/i }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText(/full name/i), 'Dup Student');
    await userEvent.type(within(dialog).getByLabelText(/email/i), 'a@x.com');
    await userEvent.type(within(dialog).getByLabelText(/department/i), 'Eng');
    await userEvent.type(within(dialog).getByLabelText(/branch/i), 'CSE');
    await userEvent.type(within(dialog).getByLabelText(/date of birth/i), '2003-05-14');
    await userEvent.click(within(dialog).getByRole('button', { name: /register student/i }));

    expect(await within(dialog).findByText('A user with this email already exists')).toBeInTheDocument();
  });

  it('deletes a student after confirmation', async () => {
    signIn();
    let deleted = false;
    const fetchMock = mockFetch((_url, init) => {
      if (init.method === 'DELETE') { deleted = true; return new Response(null, { status: 204 }); }
      return jsonResponse(page(deleted ? [] : [student(1, 'Alice Kumar')]));
    });
    renderAt('/students', <Route path="/students" element={<StudentsPage />} />);

    await userEvent.click(await screen.findByRole('button', { name: 'Delete Alice Kumar' }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }));

    expect(await screen.findByText('Deleted Alice Kumar.')).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([u, init]) => init?.method === 'DELETE' && String(u).endsWith('/students/1'))).toBe(true);
  });
});

describe('ChangePasswordPage', () => {
  it('only enables submit once the password meets the policy and matches', async () => {
    signIn({ role: 'ROLE_STUDENT', pwdChangeRequired: true });
    renderAt('/change-password', <Route path="/change-password" element={<ChangePasswordPage />} />);
    const submit = screen.getByRole('button', { name: /update password/i });

    await userEvent.type(screen.getByLabelText(/temporary password/i), 'Temp#123');
    await userEvent.type(screen.getByLabelText(/^new password/i), 'weak');
    expect(submit).toBeDisabled();

    await userEvent.clear(screen.getByLabelText(/^new password/i));
    await userEvent.type(screen.getByLabelText(/^new password/i), 'Strong@123');
    await userEvent.type(screen.getByLabelText(/confirm new password/i), 'Strong@124');
    expect(screen.getByText('Passwords do not match')).toBeInTheDocument();
    expect(submit).toBeDisabled();

    await userEvent.clear(screen.getByLabelText(/confirm new password/i));
    await userEvent.type(screen.getByLabelText(/confirm new password/i), 'Strong@123');
    expect(submit).toBeEnabled();
  });

  it('changes the password, then refreshes to an unrestricted token', async () => {
    signIn({ role: 'ROLE_STUDENT', pwdChangeRequired: true });
    const full = fakeJwt({ role: 'ROLE_STUDENT', pwdChangeRequired: false });
    const fetchMock = mockFetch((url) => (url.pathname === '/auth/refresh'
      ? jsonResponse({ accessToken: full })
      : new Response('Password changed successfully', { status: 200, headers: { 'Content-Type': 'text/plain' } })));
    renderAt('/change-password', <Route path="/change-password" element={<ChangePasswordPage />} />);

    await userEvent.type(screen.getByLabelText(/temporary password/i), 'Temp#123');
    await userEvent.type(screen.getByLabelText(/^new password/i), 'Strong@123');
    await userEvent.type(screen.getByLabelText(/confirm new password/i), 'Strong@123');
    await userEvent.click(screen.getByRole('button', { name: /update password/i }));

    expect(await screen.findByText(/password updated/i)).toBeInTheDocument();
    const paths = fetchMock.mock.calls.map(([u]) => new URL(String(u)).pathname);
    expect(paths).toEqual(['/auth/change-password', '/auth/refresh']);
    expect(localStorage.getItem('sms.accessToken')).toBe(full);
  });

  it('explains a wrong current password', async () => {
    signIn({ role: 'ROLE_STUDENT' });
    mockFetch(() => jsonResponse({ error: 'Old password is incorrect' }, 401));
    renderAt('/change-password', <Route path="/change-password" element={<ChangePasswordPage />} />);

    await userEvent.type(screen.getByLabelText(/current password/i), 'nope');
    await userEvent.type(screen.getByLabelText(/^new password/i), 'Strong@123');
    await userEvent.type(screen.getByLabelText(/confirm new password/i), 'Strong@123');
    await userEvent.click(screen.getByRole('button', { name: /update password/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Your current password is incorrect.');
  });
});
