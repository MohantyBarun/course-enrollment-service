import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { adminApi } from '../api/admin';
import { ApiError } from '../api/client';
import type { NotificationEntry, NotificationFeed, NotificationStatus } from '../api/types';
import { EmptyState, LoadingBlock, PageHeader } from '../components/academic';
import { Alert, Spinner } from '../components/ui';
import { useApi } from '../hooks/useApi';
import { usePolling } from '../hooks/usePolling';
import { explainApiError } from '../lib/backendErrors';
import { relativeTime } from '../lib/format';

const STATUS: Record<NotificationStatus, { label: string; className: string; help: string }> = {
  SENT: { label: 'Sent', className: 'bg-emerald-50 text-emerald-700 ring-emerald-200', help: 'Accepted by the SMTP server' },
  LOGGED: { label: 'Logged only', className: 'bg-slate-100 text-slate-700 ring-slate-200', help: 'Processed, but real email is off for this recipient' },
  PROCESSED: { label: 'Processed', className: 'bg-slate-100 text-slate-700 ring-slate-200', help: 'Handled before delivery records were kept' },
  QUEUED: { label: 'Queued', className: 'bg-sky-50 text-sky-700 ring-sky-200', help: 'Committed to the outbox, waiting for the relay' },
  RETRYING: { label: 'Retrying', className: 'bg-amber-50 text-amber-700 ring-amber-200', help: 'Delivery failed; retrying with exponential backoff' },
  IN_BROKER: { label: 'In RabbitMQ', className: 'bg-indigo-50 text-indigo-700 ring-indigo-200', help: 'Published to the broker, not yet consumed' },
  FAILED: { label: 'Failed', className: 'bg-rose-50 text-rose-700 ring-rose-200', help: 'Gave up after the maximum number of attempts' },
};

function StatusBadge({ status }: { status: NotificationStatus }) {
  const s = STATUS[status];
  return (
    <span title={s.help} className={`inline-flex rounded-full px-2 py-0.5 text-xs font-medium ring-1 ${s.className}`}>{s.label}</span>
  );
}

function Pipeline() {
  const steps = ['Service writes event in the same transaction', 'Outbox relay (after commit)', 'Consumer (idempotent)', 'Email sender (circuit breaker)'];
  return (
    <ol className="flex flex-wrap items-center gap-2 text-xs text-slate-600" aria-label="Notification pipeline">
      {steps.map((step, i) => (
        <li key={step} className="flex items-center gap-2">
          <span className="rounded-md bg-slate-100 px-2 py-1">{step}</span>
          {i < steps.length - 1 && <span aria-hidden="true" className="text-slate-400">→</span>}
        </li>
      ))}
    </ol>
  );
}

/** Explains, up front, who actually receives real email with the current configuration. */
function DeliveryNotice({ feed }: { feed: NotificationFeed }) {
  if (feed.emailMode.startsWith('Logged only')) {
    return (
      <Alert kind="info">
        Real email is off: {feed.emailMode.replace(/^Logged only \(|\)$/g, '')}. Everything else (the outbox,
        retries, de-duplication) runs exactly as in production. Try it:{' '}
        <Link to="/students" className="font-medium underline">register a student</Link> and watch the welcome
        email appear below within a second.
      </Alert>
    );
  }
  if (feed.allowedRecipients.length > 0) {
    return (
      <Alert kind="info">
        Real email goes only to <strong>{feed.allowedRecipients.join(', ')}</strong> (MAIL_ALLOWED_RECIPIENTS);
        emails to anyone else are logged only. To receive a welcome email,{' '}
        <Link to="/students" className="font-medium underline">register a student</Link> with an allowlisted
        address, or send a test email.
      </Alert>
    );
  }
  return null;
}

function TestEmailForm({ feed, onQueued }: { feed: NotificationFeed; onQueued: () => void }) {
  const [to, setTo] = useState('');
  const [sending, setSending] = useState(false);
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null);
  const hasDefault = feed.allowedRecipients.some((a) => !a.startsWith('@'));

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setSending(true);
    setResult(null);
    try {
      const queued = await adminApi.sendTestEmail(to.trim() || undefined);
      setResult({ ok: true, text: `Test email to ${queued.recipient} queued. Its status appears below.` });
      onQueued();
    } catch (err) {
      setResult({ ok: false, text: err instanceof ApiError ? err.message : 'Could not queue the test email.' });
    } finally {
      setSending(false);
    }
  };

  return (
    <form onSubmit={submit} className="space-y-2" noValidate>
      <label htmlFor="test-email-to" className="block text-sm font-medium text-slate-700">Send a test email</label>
      <div className="flex flex-wrap gap-2">
        <input id="test-email-to" type="email" className="input max-w-xs" value={to} onChange={(e) => setTo(e.target.value)}
               placeholder={hasDefault ? 'Blank = first allowlisted address' : 'name@example.com'} />
        <button type="submit" className="btn-secondary" disabled={sending}>{sending && <Spinner />} Send test email</button>
      </div>
      <p className="text-xs text-slate-500">Goes through the same outbox path. Limited to one every few minutes.</p>
      {result && <Alert kind={result.ok ? 'success' : 'error'}>{result.text}</Alert>}
    </form>
  );
}

function Row({ entry }: { entry: NotificationEntry }) {
  const showReason = entry.status !== 'SENT' && entry.detail;
  return (
    <li className="p-4">
      <details className="group">
        <summary className="flex cursor-pointer list-none flex-wrap items-center justify-between gap-3">
          <div className="min-w-0">
            <p className="truncate text-sm font-medium text-slate-900">{entry.subject}</p>
            <p className="truncate text-xs text-slate-500">
              {entry.recipient ?? 'unknown recipient'} · {entry.label}
            </p>
            {showReason && <p className="mt-0.5 text-xs text-slate-600">{entry.detail}</p>}
          </div>
          <div className="flex items-center gap-3 text-xs text-slate-500">
            {entry.attempts > 0 && <span>{entry.attempts} failed attempt{entry.attempts === 1 ? '' : 's'}</span>}
            <span title={entry.createdAt}>{relativeTime(entry.createdAt)}</span>
            <StatusBadge status={entry.status} />
            <span aria-hidden="true" className="text-slate-400 transition group-open:rotate-90">›</span>
          </div>
        </summary>
        <div className="mt-3 space-y-2 border-t border-slate-100 pt-3 text-xs text-slate-600">
          {entry.status === 'SENT' && entry.detail && <p><span className="font-medium text-slate-700">Status:</span> {entry.detail}</p>}
          {entry.nextAttemptAt && <p><span className="font-medium text-slate-700">Next attempt:</span> {relativeTime(entry.nextAttemptAt)}</p>}
          <p><span className="font-medium text-slate-700">Message id:</span> <code>{entry.id}</code></p>
          {entry.preview && (
            <pre className="whitespace-pre-wrap rounded-lg bg-slate-50 p-3 font-sans text-slate-700">{entry.preview}</pre>
          )}
        </div>
      </details>
    </li>
  );
}

export function EmailsPage() {
  const feed = useApi(adminApi.notifications);
  usePolling(feed.reload, 5000);
  const data = feed.data;
  const error = explainApiError(feed.error);

  return (
    <div className="space-y-6">
      <PageHeader title="Emails"
                  subtitle="Every notification goes through the transactional outbox. Refreshes every 5 seconds." />
      {error && <Alert>{error}</Alert>}
      {!data ? (!error && <LoadingBlock />) : (
        <>
          <div className="card space-y-4 p-5">
            <Pipeline />
            <dl className="grid grid-cols-1 gap-2 text-sm sm:grid-cols-2">
              <div><dt className="inline font-medium text-slate-700">Delivery: </dt><dd className="inline text-slate-600">{data.emailMode}</dd></div>
              <div><dt className="inline font-medium text-slate-700">Transport: </dt><dd className="inline text-slate-600">{data.transport}</dd></div>
            </dl>
            <DeliveryNotice feed={data} />
            <TestEmailForm feed={data} onQueued={feed.reload} />
          </div>

          <div className="card">
            {data.entries.length === 0 ? (
              <EmptyState title="No emails yet">
                Registering a student or enrolling in a course creates one.
              </EmptyState>
            ) : (
              <ul className="divide-y divide-slate-100">
                {data.entries.map((e) => <Row key={e.id} entry={e} />)}
              </ul>
            )}
          </div>
        </>
      )}
    </div>
  );
}
