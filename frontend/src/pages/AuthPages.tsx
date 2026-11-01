import { useState, type FormEvent, type ReactNode } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { ApiError } from '../api/client';
import { authApi } from '../api/endpoints';
import { homePathFor, useAuth, type Session } from '../auth/AuthContext';
import { Logo } from '../components/Logo';
import { Alert, Field, PageHeader, Spinner } from '../components/ui';
import { useApi } from '../hooks/useApi';
import { isPasswordValid, PASSWORD_RULES } from '../lib/passwordRules';

function useAfterSignIn() {
  const navigate = useNavigate();
  const from = (useLocation().state as { from?: string } | null)?.from;
  return (session: Session) => {
    // Admins can't book, so don't send them back into checkout
    const target = from && !(session.role === 'ROLE_ADMIN' && /^\/(checkout|pay|shows)/.test(from)) ? from : homePathFor(session);
    navigate(target, { replace: true });
  };
}

function AuthShell({ title, subtitle, children }: { title: string; subtitle: ReactNode; children: ReactNode }) {
  return (
    <div className="grid min-h-[calc(100vh-4rem)] lg:grid-cols-2">
      <div className="relative hidden overflow-hidden bg-marquee lg:block">
        <div className="absolute inset-0 bg-[radial-gradient(circle_at_30%_20%,rgba(255,255,255,0.35),transparent_45%),radial-gradient(circle_at_80%_80%,rgba(124,58,237,0.6),transparent_50%)]" aria-hidden="true" />
        <div className="relative flex h-full flex-col justify-end p-12 text-ink-950">
          <p className="text-5xl font-extrabold leading-tight tracking-tight">Your seat,<br />locked in<br />while you pay.</p>
          <p className="mt-4 max-w-sm text-sm font-medium text-ink-950/75">
            Live seat maps, 10-minute holds, and a waitlist that hands you seats the moment they free up.
          </p>
        </div>
      </div>
      <div className="flex items-center justify-center px-4 py-12">
        <div className="w-full max-w-sm space-y-6 animate-fade-up">
          <div className="lg:hidden"><Logo /></div>
          <div>
            <h1 className="text-2xl font-extrabold text-white">{title}</h1>
            <p className="mt-1 text-sm text-ink-400">{subtitle}</p>
          </div>
          {children}
        </div>
      </div>
    </div>
  );
}

function DemoButtons() {
  const { loginAsDemo } = useAuth();
  const after = useAfterSignIn();
  const demo = useApi(authApi.demoStatus);
  const [busy, setBusy] = useState<'CUSTOMER' | 'ADMIN' | null>(null);
  const [error, setError] = useState<string | null>(null);
  if (!demo.data?.enabled) return null;

  const go = async (role: 'CUSTOMER' | 'ADMIN') => {
    setBusy(role);
    setError(null);
    try {
      after(await loginAsDemo(role));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Demo sign-in failed.');
      setBusy(null);
    }
  };

  return (
    <div className="space-y-3">
      <div className="grid grid-cols-2 gap-3">
        <button type="button" className="btn-secondary flex-col gap-0 rounded-2xl py-3" onClick={() => go('CUSTOMER')} disabled={busy !== null}>
          <span className="flex items-center gap-2">{busy === 'CUSTOMER' ? <Spinner /> : '🍿'} Demo customer</span>
          <span className="text-[11px] font-normal text-ink-400">Book tickets</span>
        </button>
        <button type="button" className="btn-secondary flex-col gap-0 rounded-2xl py-3" onClick={() => go('ADMIN')} disabled={busy !== null}>
          <span className="flex items-center gap-2">{busy === 'ADMIN' ? <Spinner /> : '🛠️'} Demo admin</span>
          <span className="text-[11px] font-normal text-ink-400">Run the cinemas</span>
        </button>
      </div>
      {error && <Alert>{error}</Alert>}
      <div className="flex items-center gap-3 text-xs text-ink-500">
        <span className="h-px flex-1 bg-white/10" />or use your account<span className="h-px flex-1 bg-white/10" />
      </div>
    </div>
  );
}

export function LoginPage() {
  const { session, login } = useAuth();
  const after = useAfterSignIn();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  if (session) return <Navigate to={homePathFor(session)} replace />;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setFieldErrors({});
    try {
      after(await login(email.trim(), password));
    } catch (err) {
      setBusy(false);
      if (err instanceof ApiError) {
        setFieldErrors(err.fieldErrors);
        setError(err.message);
      } else setError('Sign-in failed.');
    }
  };

  return (
    <AuthShell title="Welcome back" subtitle={<>New here? <Link to="/register" state={location.state} className="font-semibold text-sun-300 hover:underline">Create an account</Link></>}>
      <DemoButtons />
      <form onSubmit={submit} className="space-y-4" noValidate>
        {error && <Alert>{error}</Alert>}
        <Field label="Email" type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} error={fieldErrors.email} required />
        <Field label="Password" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} error={fieldErrors.password} required />
        <button type="submit" className="btn-primary w-full py-3" disabled={busy}>{busy && <Spinner />} Sign in</button>
      </form>
    </AuthShell>
  );
}

function PasswordChecklist({ password }: { password: string }) {
  return (
    <ul className="grid grid-cols-2 gap-1 text-xs" aria-label="Password requirements">
      {PASSWORD_RULES.map((r) => {
        const ok = r.test(password);
        return <li key={r.label} className={ok ? 'text-mint-300' : 'text-ink-500'}>{ok ? '✓' : '○'} {r.label}</li>;
      })}
    </ul>
  );
}

export function RegisterPage() {
  const { session, register } = useAuth();
  const after = useAfterSignIn();
  const location = useLocation();
  const [form, setForm] = useState({ name: '', email: '', phone: '', password: '' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  if (session) return <Navigate to={homePathFor(session)} replace />;
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value });

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!isPasswordValid(form.password)) {
      setFieldErrors({ password: 'Choose a password that meets every rule below.' });
      return;
    }
    setBusy(true);
    setError(null);
    setFieldErrors({});
    try {
      after(await register({ ...form, name: form.name.trim(), email: form.email.trim(), phone: form.phone.trim() }));
    } catch (err) {
      setBusy(false);
      if (err instanceof ApiError) {
        setFieldErrors(err.fieldErrors);
        setError(err.message);
      } else setError('Sign-up failed.');
    }
  };

  return (
    <AuthShell title="Create your account" subtitle={<>Already have one? <Link to="/login" state={location.state} className="font-semibold text-sun-300 hover:underline">Sign in</Link></>}>
      <form onSubmit={submit} className="space-y-4" noValidate>
        {error && <Alert>{error}</Alert>}
        <Field label="Name" autoComplete="name" value={form.name} onChange={set('name')} error={fieldErrors.name} required />
        <Field label="Email" type="email" autoComplete="email" value={form.email} onChange={set('email')} error={fieldErrors.email} required />
        <Field label="Phone (optional)" type="tel" autoComplete="tel" value={form.phone} onChange={set('phone')} error={fieldErrors.phone} placeholder="+91 98765 43210" />
        <div className="space-y-2">
          <Field label="Password" type="password" autoComplete="new-password" value={form.password} onChange={set('password')} error={fieldErrors.password} required />
          <PasswordChecklist password={form.password} />
        </div>
        <button type="submit" className="btn-primary w-full py-3" disabled={busy}>{busy && <Spinner />} Create account</button>
      </form>
    </AuthShell>
  );
}

export function AccountPage() {
  const me = useApi(authApi.me);
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!isPasswordValid(next)) {
      setFieldErrors({ newPassword: 'Choose a password that meets every rule below.' });
      return;
    }
    setBusy(true);
    setFieldErrors({});
    try {
      await authApi.changePassword(current, next);
      setCurrent('');
      setNext('');
      setResult({ ok: true, text: 'Password changed. Your other sessions were signed out.' });
    } catch (err) {
      if (err instanceof ApiError) setFieldErrors(err.fieldErrors);
      setResult({ ok: false, text: err instanceof ApiError ? err.message : 'Could not change the password.' });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="mx-auto max-w-2xl space-y-6 px-4 py-8 sm:px-6">
      <PageHeader title="Account" />
      <section className="card p-6">
        {me.error && <Alert>{me.error}</Alert>}
        {me.data && (
          <div className="flex items-center gap-4">
            <span className="grid h-14 w-14 place-items-center rounded-2xl bg-marquee text-xl font-extrabold text-ink-950">
              {me.data.name.charAt(0).toUpperCase()}
            </span>
            <div>
              <p className="text-lg font-bold text-white">{me.data.name}</p>
              <p className="text-sm text-ink-300">{me.data.email}{me.data.phone ? ` · ${me.data.phone}` : ''}</p>
              <p className="text-xs text-ink-500">{me.data.role === 'ROLE_ADMIN' ? 'Administrator' : 'Customer'}</p>
            </div>
          </div>
        )}
      </section>
      <section className="card p-6">
        <h2 className="mb-4 font-bold text-white">Change password</h2>
        <form onSubmit={submit} className="space-y-4" noValidate>
          {result && <Alert kind={result.ok ? 'success' : 'error'}>{result.text}</Alert>}
          <Field label="Current password" type="password" autoComplete="current-password" value={current}
                 onChange={(e) => setCurrent(e.target.value)} error={fieldErrors.currentPassword} />
          <div className="space-y-2">
            <Field label="New password" type="password" autoComplete="new-password" value={next}
                   onChange={(e) => setNext(e.target.value)} error={fieldErrors.newPassword} />
            <PasswordChecklist password={next} />
          </div>
          <button type="submit" className="btn-primary" disabled={busy || !current}>{busy && <Spinner />} Update password</button>
        </form>
      </section>
    </div>
  );
}

export function NotFoundPage() {
  return (
    <div className="mx-auto flex max-w-md flex-col items-center px-4 py-24 text-center">
      <p className="text-gradient text-7xl font-extrabold">404</p>
      <h1 className="mt-2 text-xl font-bold text-white">This scene was cut</h1>
      <p className="mt-1 text-sm text-ink-400">The page you're looking for doesn't exist.</p>
      <Link to="/" className="btn-primary mt-6">Back to movies</Link>
    </div>
  );
}
