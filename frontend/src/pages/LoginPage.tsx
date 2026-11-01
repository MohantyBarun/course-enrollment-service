import { useEffect, useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { demoApi } from '../api/academics';
import { ApiError } from '../api/client';
import { homePathFor, useAuth } from '../auth/AuthContext';
import { Brand, WakeUpNotice } from '../components/Layout';
import { Alert, Field, Spinner } from '../components/ui';

export function LoginPage() {
  const { session, login, loginAsDemo } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);
  const [demoEnabled, setDemoEnabled] = useState(false);
  const [demoPending, setDemoPending] = useState<'ADMIN' | 'STUDENT' | null>(null);

  useEffect(() => {
    demoApi.status().then((s) => setDemoEnabled(s.enabled)).catch(() => setDemoEnabled(false));
  }, []);

  if (session) return <Navigate to={homePathFor(session)} replace />;

  const tryDemo = async (role: 'ADMIN' | 'STUDENT') => {
    setError(null);
    setDemoPending(role);
    try {
      const next = await loginAsDemo(role);
      navigate(homePathFor(next), { replace: true });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not start the demo. Please try again.');
    } finally {
      setDemoPending(null);
    }
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setFieldErrors({});
    setSubmitting(true);
    try {
      const next = await login(email.trim(), password);
      const from = (location.state as { from?: string } | null)?.from;
      navigate(next.mustChangePassword ? '/change-password' : from ?? homePathFor(next), { replace: true });
    } catch (err) {
      if (err instanceof ApiError) {
        setFieldErrors(err.fieldErrors);
        // Don't reveal whether the account exists
        setError(err.status === 404 ? 'Invalid email or password.' : err.message);
      } else {
        setError('Something went wrong. Please try again.');
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="flex min-h-screen flex-col">
      <WakeUpNotice />
      <div className="flex flex-1 items-center justify-center bg-gradient-to-br from-indigo-50 via-slate-50 to-white px-4 py-12">
        <div className="w-full max-w-sm">
          <div className="mb-8 flex flex-col items-center gap-3 text-center">
            <Brand />
            <h1 className="text-2xl font-semibold text-slate-900">Sign in to your account</h1>
            <p className="text-sm text-slate-500">Courses, enrollment, attendance and grades in one place.</p>
          </div>
          {demoEnabled && (
            <div className="card mb-4 border-indigo-200 bg-indigo-50/60 p-5">
              <p className="text-sm font-semibold text-indigo-900">Explore the live demo</p>
              <p className="mt-1 text-xs text-indigo-800/80">
                No sign-up needed. Sample data includes courses, grades, attendance and notices.
              </p>
              <div className="mt-3 grid grid-cols-2 gap-2">
                <button type="button" className="btn-primary" disabled={demoPending !== null} onClick={() => tryDemo('ADMIN')}>
                  {demoPending === 'ADMIN' && <Spinner />} Try as Admin
                </button>
                <button type="button" className="btn-secondary" disabled={demoPending !== null} onClick={() => tryDemo('STUDENT')}>
                  {demoPending === 'STUDENT' && <Spinner />} Try as Student
                </button>
              </div>
            </div>
          )}
          <form onSubmit={handleSubmit} className="card space-y-4 p-6" noValidate>
            {error && <Alert>{error}</Alert>}
            <Field label="Email" type="email" autoComplete="username" required value={email}
                   onChange={(e) => setEmail(e.target.value)} error={fieldErrors.email} />
            <Field label="Password" type="password" autoComplete="current-password" required value={password}
                   onChange={(e) => setPassword(e.target.value)} error={fieldErrors.password} />
            <button type="submit" className="btn-primary w-full" disabled={submitting}>
              {submitting && <Spinner />} {submitting ? 'Signing in…' : 'Sign in'}
            </button>
            <p className="text-center text-xs text-slate-500">
              New student? Use the temporary password from your administrator. You'll be asked to change it.
            </p>
          </form>
        </div>
      </div>
    </div>
  );
}
