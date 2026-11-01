import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError } from '../api/client';
import { homePathFor, useAuth } from '../auth/AuthContext';
import { Alert, Field, Spinner } from '../components/ui';
import { isPasswordValid, PASSWORD_RULES } from '../lib/passwordRules';

export function ChangePasswordPage() {
  const { session, changePassword } = useAuth();
  const navigate = useNavigate();
  const [oldPassword, setOldPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [done, setDone] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const firstLogin = session?.mustChangePassword ?? false;
  const mismatch = confirm.length > 0 && confirm !== newPassword;
  const canSubmit = oldPassword && isPasswordValid(newPassword) && confirm === newPassword && !submitting;

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!canSubmit) return;
    setError(null);
    setFieldErrors({});
    setSubmitting(true);
    try {
      await changePassword(oldPassword, newPassword, confirm);
      setDone(true);
      setOldPassword('');
      setNewPassword('');
      setConfirm('');
    } catch (err) {
      if (err instanceof ApiError) {
        setFieldErrors(err.fieldErrors);
        setError(err.status === 401 ? 'Your current password is incorrect.' : err.message);
      } else {
        setError('Something went wrong. Please try again.');
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="mx-auto max-w-md">
      <h1 className="text-2xl font-semibold text-slate-900">
        {firstLogin ? 'Set your password' : 'Change password'}
      </h1>
      <p className="mt-1 text-sm text-slate-500">
        {firstLogin
          ? "You signed in with a temporary password. Choose your own password to continue."
          : 'Use a strong password you don’t use anywhere else.'}
      </p>

      <form onSubmit={handleSubmit} className="card mt-6 space-y-4 p-6" noValidate>
        {error && <Alert>{error}</Alert>}
        {done && (
          <Alert kind="success">
            Password updated.{' '}
            {session && (
              <button type="button" className="font-medium underline" onClick={() => navigate(homePathFor(session))}>
                Continue
              </button>
            )}
          </Alert>
        )}
        <Field label={firstLogin ? 'Temporary password' : 'Current password'} type="password"
               autoComplete="current-password" required value={oldPassword}
               onChange={(e) => setOldPassword(e.target.value)} error={fieldErrors.oldPassword} />
        <Field label="New password" type="password" autoComplete="new-password" required value={newPassword}
               onChange={(e) => setNewPassword(e.target.value)} error={fieldErrors.newPassword} />
        <ul className="grid grid-cols-1 gap-1 text-xs sm:grid-cols-2" aria-label="Password requirements">
          {PASSWORD_RULES.map((rule) => {
            const ok = rule.test(newPassword);
            return (
              <li key={rule.label} className={ok ? 'text-emerald-600' : 'text-slate-500'}>
                {ok ? '✓' : '○'} {rule.label}
              </li>
            );
          })}
        </ul>
        <Field label="Confirm new password" type="password" autoComplete="new-password" required value={confirm}
               onChange={(e) => setConfirm(e.target.value)}
               error={mismatch ? 'Passwords do not match' : fieldErrors.confirmNewPassword} />
        <button type="submit" className="btn-primary w-full" disabled={!canSubmit}>
          {submitting && <Spinner />} {submitting ? 'Saving…' : 'Update password'}
        </button>
      </form>
    </div>
  );
}
