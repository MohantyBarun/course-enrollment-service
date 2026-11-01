import { useState, type FormEvent, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, apiRequest } from '../../api/client';
import { adminApi } from '../../api/endpoints';
import type { NotificationEntry, NotificationFeed, NotificationStatus, SystemSnapshot } from '../../api/types';
import { Alert, Badge, EmptyState, LoadingBlock, PageHeader, ProgressBar, Spinner, StatCard, type Tone } from '../../components/ui';
import { useApi } from '../../hooks/useApi';
import { usePolling } from '../../hooks/usePolling';
import { explainApiError } from '../../lib/backendErrors';
import { formatDuration, relativeTime } from '../../lib/format';

// ------------------------------------------------------------------ Emails

const STATUS: Record<NotificationStatus, { label: string; tone: Tone; help: string }> = {
  SENT: { label: 'Sent', tone: 'mint', help: 'Accepted by the SMTP server' },
  LOGGED: { label: 'Logged only', tone: 'ink', help: 'Processed, but real email is off for this recipient' },
  PROCESSED: { label: 'Processed', tone: 'ink', help: 'Handled before delivery records were kept' },
  QUEUED: { label: 'Queued', tone: 'grape', help: 'Committed to the outbox, waiting for the relay' },
  RETRYING: { label: 'Retrying', tone: 'sun', help: 'Delivery failed; retrying with exponential backoff' },
  IN_BROKER: { label: 'In RabbitMQ', tone: 'grape', help: 'Published to the broker, not yet consumed' },
  FAILED: { label: 'Failed', tone: 'flame', help: 'Gave up after the maximum number of attempts' },
};

function Pipeline() {
  const steps = ['Service writes the event in the same transaction', 'Outbox relay (after commit)', 'Consumer (idempotent)', 'Email sender (circuit breaker)'];
  return (
    <ol className="flex flex-wrap items-center gap-2 text-xs text-ink-300" aria-label="Notification pipeline">
      {steps.map((step, i) => (
        <li key={step} className="flex items-center gap-2">
          <span className="rounded-lg bg-white/5 px-2 py-1">{step}</span>
          {i < steps.length - 1 && <span aria-hidden="true" className="text-sun-400">→</span>}
        </li>
      ))}
    </ol>
  );
}

function DeliveryNotice({ feed }: { feed: NotificationFeed }) {
  if (feed.emailMode.startsWith('Logged only')) {
    return (
      <Alert kind="info">
        Real email is off: {feed.emailMode.replace(/^Logged only \(|\)$/g, '')}. Everything else (the outbox, retries,
        de-duplication) runs exactly as in production. Try it: <Link to="/" className="font-semibold underline">book a ticket</Link> as
        a customer and watch the confirmation appear below within a second.
      </Alert>
    );
  }
  if (feed.allowedRecipients.length > 0) {
    return (
      <Alert kind="info">
        Real email goes only to <strong>{feed.allowedRecipients.join(', ')}</strong> (MAIL_ALLOWED_RECIPIENTS); emails to
        anyone else are logged only. Send a test email to see one arrive.
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
      <label htmlFor="test-email-to" className="label">Send a test email</label>
      <div className="flex flex-wrap gap-2">
        <input id="test-email-to" type="email" className="input max-w-xs" value={to} onChange={(e) => setTo(e.target.value)}
               placeholder={hasDefault ? 'Blank = first allowlisted address' : 'name@example.com'} />
        <button type="submit" className="btn-secondary" disabled={sending}>{sending && <Spinner />} Send test email</button>
      </div>
      <p className="text-xs text-ink-500">Goes through the same outbox path. Limited to one every few minutes.</p>
      {result && <Alert kind={result.ok ? 'success' : 'error'}>{result.text}</Alert>}
    </form>
  );
}

function EmailRow({ entry }: { entry: NotificationEntry }) {
  const s = STATUS[entry.status];
  return (
    <li className="p-4">
      <details className="group">
        <summary className="flex cursor-pointer list-none flex-wrap items-center justify-between gap-3">
          <div className="min-w-0">
            <p className="truncate text-sm font-semibold text-white">{entry.subject}</p>
            <p className="truncate text-xs text-ink-400">{entry.recipient ?? 'unknown recipient'} · {entry.label}</p>
            {entry.status !== 'SENT' && entry.detail && <p className="mt-0.5 text-xs text-ink-300">{entry.detail}</p>}
          </div>
          <div className="flex items-center gap-3 text-xs text-ink-400">
            {entry.attempts > 0 && <span>{entry.attempts} failed attempt{entry.attempts === 1 ? '' : 's'}</span>}
            <span title={entry.createdAt}>{relativeTime(entry.createdAt)}</span>
            <Badge tone={s.tone} title={s.help}>{s.label}</Badge>
            <span aria-hidden="true" className="transition group-open:rotate-90">›</span>
          </div>
        </summary>
        <div className="mt-3 space-y-2 border-t border-white/5 pt-3 text-xs text-ink-300">
          {entry.status === 'SENT' && entry.detail && <p><span className="font-semibold text-ink-200">Status:</span> {entry.detail}</p>}
          {entry.nextAttemptAt && <p><span className="font-semibold text-ink-200">Next attempt:</span> {relativeTime(entry.nextAttemptAt)}</p>}
          <p><span className="font-semibold text-ink-200">Message id:</span> <code>{entry.id}</code></p>
          {entry.preview && <pre className="whitespace-pre-wrap rounded-xl bg-ink-950 p-3 font-sans text-ink-200">{entry.preview}</pre>}
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
      <PageHeader title="Emails" subtitle="Every notification goes through the transactional outbox. Refreshes every 5 seconds." />
      {error && <Alert>{error}</Alert>}
      {!data ? (!error && <LoadingBlock />) : (
        <>
          <div className="card space-y-4 p-5">
            <Pipeline />
            <dl className="grid grid-cols-1 gap-2 text-sm sm:grid-cols-2">
              <div><dt className="inline font-semibold text-ink-200">Delivery: </dt><dd className="inline text-ink-300">{data.emailMode}</dd></div>
              <div><dt className="inline font-semibold text-ink-200">Transport: </dt><dd className="inline text-ink-300">{data.transport}</dd></div>
            </dl>
            <DeliveryNotice feed={data} />
            <TestEmailForm feed={data} onQueued={feed.reload} />
          </div>
          <div className="card">
            {data.entries.length === 0 ? (
              <EmptyState icon="✉️" title="No emails yet">Signing up, booking or cancelling creates one.</EmptyState>
            ) : (
              <ul className="divide-y divide-white/5">{data.entries.map((e) => <EmailRow key={e.id} entry={e} />)}</ul>
            )}
          </div>
        </>
      )}
    </div>
  );
}

// ------------------------------------------------------------------ System

function Section({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  return (
    <section className="card p-5">
      <div className="mb-4">
        <h2 className="text-sm font-bold text-white">{title}</h2>
        {subtitle && <p className="mt-0.5 text-xs text-ink-400">{subtitle}</p>}
      </div>
      {children}
    </section>
  );
}

function Table({ head, rows }: { head: string[]; rows: ReactNode[][] }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <thead className="text-xs uppercase tracking-wider text-ink-400">
          <tr>{head.map((h) => <th key={h} className="pb-2 pr-4 font-semibold">{h}</th>)}</tr>
        </thead>
        <tbody className="divide-y divide-white/5">
          {rows.map((r, i) => <tr key={i}>{r.map((c, j) => <td key={j} className="py-2 pr-4 text-ink-200">{c}</td>)}</tr>)}
        </tbody>
      </table>
    </div>
  );
}

/** Fires wrong logins for a probe address until the per-IP login limit answers 429. */
function RateLimitProbe({ limit, onDone }: { limit: number; onDone: () => void }) {
  const [running, setRunning] = useState(false);
  const [results, setResults] = useState<number[]>([]);
  const attempts = Math.min(limit + 2, 30);

  const run = async () => {
    setRunning(true);
    setResults([]);
    for (let i = 0; i < attempts; i++) {
      let status = 200;
      try {
        await apiRequest('/auth/login', {
          method: 'POST', auth: false, body: { email: 'rate-limit-probe@seatsure.dev', password: 'Wrong-password-1' },
        });
      } catch (err) {
        status = err instanceof ApiError ? err.status : 0;
      }
      setResults((r) => [...r, status]);
    }
    setRunning(false);
    onDone();
  };

  const blocked = results.filter((s) => s === 429).length;
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-3">
        <button type="button" className="btn-secondary" onClick={run} disabled={running}>{running && <Spinner />} Send {attempts} wrong logins</button>
        <p className="text-xs text-ink-500">Uses your address's login allowance for about a minute. You stay signed in.</p>
      </div>
      {results.length > 0 && (
        <div aria-label="Probe results">
          <div className="flex flex-wrap gap-1">
            {results.map((s, i) => (
              <span key={i} className={`rounded px-1.5 py-0.5 font-mono text-xs ${s === 429 ? 'bg-flame-500/20 text-flame-300' : 'bg-white/5 text-ink-300'}`}>
                {s === 0 ? 'ERR' : s}
              </span>
            ))}
          </div>
          {!running && (
            <p className="mt-2 text-xs text-ink-300">
              {blocked > 0
                ? `${results.length - blocked} attempts got through (unknown account → 401), then ${blocked} were rejected with 429 Too Many Requests.`
                : 'No request was rate limited (the limit may have been raised or disabled).'}
            </p>
          )}
        </div>
      )}
    </div>
  );
}

function BreakerBadge({ state }: { state: string }) {
  return <Badge tone={state === 'CLOSED' ? 'mint' : state === 'OPEN' ? 'flame' : 'sun'}>{state}</Badge>;
}

export function SystemView({ data, reload }: { data: SystemSnapshot; reload: () => void }) {
  const { infrastructure: infra, http, outbox, rateLimits, jvm } = data;
  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
        <StatCard label="Requests" value={http.requests} hint="excluding admin polling" />
        <StatCard label="Mean latency" value={`${http.meanMs} ms`} hint={`max ${http.maxMs} ms`} accent="from-grape-500 to-flame-500" />
        <StatCard label="4xx" value={http.clientErrors} accent="from-sun-400 to-sun-300" />
        <StatCard label="5xx" value={http.serverErrors} accent={http.serverErrors ? 'from-flame-600 to-flame-400' : 'from-mint-400 to-mint-300'} />
        <StatCard label="Uptime" value={formatDuration(jvm.uptimeSeconds)} hint="counters reset on restart" accent="from-mint-400 to-grape-500" />
      </div>
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <Section title="Infrastructure" subtitle="What this deployment runs on; each part falls back gracefully">
          <dl className="space-y-2 text-sm">
            {([
              ['Cache', infra.cache], ['Rate limiter', infra.rateLimiter], ['Messaging', infra.messaging], ['Email', infra.email],
              ['Trace export (OTLP)', infra.tracingExport ? 'On' : 'Off (trace ids still in every log line)'], ['Demo mode', infra.demoMode ? 'On' : 'Off'],
            ] as const).map(([k, v]) => (
              <div key={k} className="flex flex-wrap justify-between gap-2">
                <dt className="font-semibold text-ink-200">{k}</dt><dd className="text-right text-ink-300">{v}</dd>
              </div>
            ))}
          </dl>
        </Section>
        <Section title="Business operations" subtitle="@Observed timers on the service methods">
          <Table head={['Operation', 'Calls', 'Failed', 'Mean']} rows={data.operations.map((o) => [o.name, o.calls, o.failures, `${o.meanMs} ms`])} />
        </Section>
        <Section title="Caches" subtitle="Evicted after commit; errors fall back to the database">
          {data.caches.length === 0 ? <p className="text-sm text-ink-400">No caches.</p> : (
            <div className="space-y-4">
              {data.caches.map((c) => (
                <div key={c.name}>
                  <div className="mb-1 flex flex-wrap justify-between gap-2 text-sm">
                    <span className="font-semibold text-ink-200">{c.name}</span>
                    <span className="text-xs text-ink-400">
                      {c.hitRate == null ? 'no reads yet' : `${c.hitRate}% hits`} · {c.hits} hits / {c.misses} misses{c.size != null && ` · ${c.size} entries`}
                    </span>
                  </div>
                  <ProgressBar value={c.hitRate ?? 0} tone="good" />
                </div>
              ))}
            </div>
          )}
        </Section>
        <Section title="Outbox" subtitle="Events waiting for, or given up on, delivery">
          <div className="grid grid-cols-3 gap-3 text-center">
            {([['Pending', outbox.pending, outbox.pending ? 'text-sun-300' : 'text-white'],
               ['Failed', outbox.failed, outbox.failed ? 'text-flame-400' : 'text-white'],
               ['Published', outbox.published, 'text-white']] as const).map(([label, value, cls]) => (
              <div key={label} className="rounded-xl bg-white/[0.04] p-3">
                <p className={`text-2xl font-extrabold ${cls}`}>{value}</p>
                <p className="text-xs text-ink-400">{label}</p>
              </div>
            ))}
          </div>
          <p className="mt-3 text-xs text-ink-400">
            Since restart: {outbox.relayedPublished} delivered, {outbox.relayedRetries} retried, {outbox.relayedFailed} gave up.
          </p>
        </Section>
        <Section title="Rate limiting" subtitle="Per client IP, fixed one-minute windows">
          <Table head={['Rule', 'Limit / min', 'Rejected']}
                 rows={Object.entries(rateLimits.limitsPerMinute).map(([rule, limit]) => [rule, limit, rateLimits.rejectedByRule[rule] ?? 0])} />
          {rateLimits.backendErrors > 0 && <p className="mt-2 text-xs text-sun-300">{rateLimits.backendErrors} checks failed open because Redis was unavailable.</p>}
          <div className="mt-4 border-t border-white/5 pt-4"><RateLimitProbe limit={rateLimits.limitsPerMinute.login ?? 10} onDone={reload} /></div>
        </Section>
        <Section title="Circuit breakers" subtitle="Stop calling a failing dependency, retry it later">
          <Table head={['Dependency', 'State', 'Failure rate', 'Calls', 'Short-circuited']}
                 rows={data.circuitBreakers.map((b) => [b.name, <BreakerBadge key={b.name} state={b.state} />,
                   b.failureRate == null ? '—' : `${b.failureRate}%`, b.bufferedCalls, b.notPermittedCalls])} />
        </Section>
        <Section title="JVM">
          <div className="mb-1 flex justify-between text-sm">
            <span className="font-semibold text-ink-200">Heap</span>
            <span className="text-xs text-ink-400">{jvm.heapUsedMb} / {jvm.heapMaxMb} MB · {jvm.threads} threads</span>
          </div>
          <ProgressBar value={jvm.heapUsedMb} max={jvm.heapMaxMb} tone={jvm.heapUsedMb / jvm.heapMaxMb > 0.85 ? 'bad' : 'brand'} />
        </Section>
      </div>
    </div>
  );
}

export function SystemPage() {
  const status = useApi(adminApi.system);
  usePolling(status.reload, 5000);
  const error = explainApiError(status.error);
  return (
    <div className="space-y-6">
      <PageHeader title="System" subtitle="Live numbers from this server's metrics registry (the same one /actuator/prometheus exports). Refreshes every 5 seconds." />
      {error && <Alert>{error}</Alert>}
      {!status.data ? (!error && <LoadingBlock />) : <SystemView data={status.data} reload={status.reload} />}
    </div>
  );
}
