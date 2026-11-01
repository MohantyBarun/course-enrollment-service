import { useState, type ReactNode } from 'react';
import { adminApi } from '../api/admin';
import { ApiError, apiRequest } from '../api/client';
import type { SystemSnapshot } from '../api/types';
import { LoadingBlock, PageHeader, ProgressBar, StatCard } from '../components/academic';
import { Alert, Spinner } from '../components/ui';
import { useApi } from '../hooks/useApi';
import { usePolling } from '../hooks/usePolling';
import { explainApiError } from '../lib/backendErrors';
import { formatDuration } from '../lib/format';

function Section({ title, subtitle, children, action }: { title: string; subtitle?: string; children: ReactNode; action?: ReactNode }) {
  return (
    <section className="card p-5">
      <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-sm font-semibold text-slate-900">{title}</h2>
          {subtitle && <p className="mt-0.5 text-xs text-slate-500">{subtitle}</p>}
        </div>
        {action}
      </div>
      {children}
    </section>
  );
}

function BreakerBadge({ state }: { state: string }) {
  const cls = state === 'CLOSED' ? 'bg-emerald-50 text-emerald-700 ring-emerald-200'
    : state === 'OPEN' ? 'bg-rose-50 text-rose-700 ring-rose-200'
    : 'bg-amber-50 text-amber-700 ring-amber-200';
  return <span className={`inline-flex rounded-full px-2 py-0.5 text-xs font-medium ring-1 ${cls}`}>{state}</span>;
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
          method: 'POST', auth: false, body: { email: 'rate-limit-probe@studenthub.dev', password: 'Wrong-password-1' },
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
        <button type="button" className="btn-secondary" onClick={run} disabled={running}>
          {running && <Spinner />} Send {attempts} wrong logins
        </button>
        <p className="text-xs text-slate-500">
          Uses your address's login allowance for about a minute. You stay signed in.
        </p>
      </div>
      {results.length > 0 && (
        <div aria-label="Probe results">
          <div className="flex flex-wrap gap-1">
            {results.map((s, i) => (
              <span key={i} className={`rounded px-1.5 py-0.5 font-mono text-xs ${s === 429 ? 'bg-rose-100 text-rose-700' : 'bg-slate-100 text-slate-600'}`}>
                {s === 0 ? 'ERR' : s}
              </span>
            ))}
          </div>
          {!running && (
            <p className="mt-2 text-xs text-slate-600">
              {blocked > 0
                ? `${results.length - blocked} attempts got through (unknown account → 404), then ${blocked} were rejected with 429 Too Many Requests.`
                : 'No request was rate limited (the limit may have been raised or disabled).'}
            </p>
          )}
        </div>
      )}
    </div>
  );
}

function Table({ head, rows }: { head: string[]; rows: ReactNode[][] }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <thead className="text-xs uppercase tracking-wide text-slate-500">
          <tr>{head.map((h) => <th key={h} className="pb-2 pr-4 font-medium">{h}</th>)}</tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {rows.map((r, i) => <tr key={i}>{r.map((c, j) => <td key={j} className="py-2 pr-4 text-slate-700">{c}</td>)}</tr>)}
        </tbody>
      </table>
    </div>
  );
}

export function SystemView({ data, reload }: { data: SystemSnapshot; reload: () => void }) {
  const { infrastructure: infra, http, outbox, rateLimits, jvm } = data;
  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
        <StatCard label="Requests" value={http.requests} hint="excluding admin polling" />
        <StatCard label="Mean latency" value={`${http.meanMs} ms`} hint={`max ${http.maxMs} ms`} />
        <StatCard label="4xx responses" value={http.clientErrors} tone={http.clientErrors ? 'warn' : 'default'} />
        <StatCard label="5xx responses" value={http.serverErrors} tone={http.serverErrors ? 'bad' : 'good'} />
        <StatCard label="Uptime" value={formatDuration(jvm.uptimeSeconds)} hint="counters reset on restart" />
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <Section title="Infrastructure" subtitle="What this deployment runs on; each part falls back gracefully">
          <dl className="space-y-2 text-sm">
            {([
              ['Cache', infra.cache],
              ['Rate limiter', infra.rateLimiter],
              ['Messaging', infra.messaging],
              ['Email', infra.email],
              ['Trace export (OTLP)', infra.tracingExport ? 'On' : 'Off (trace ids still in every log line)'],
              ['Demo mode', infra.demoMode ? 'On' : 'Off'],
            ] as const).map(([k, v]) => (
              <div key={k} className="flex flex-wrap justify-between gap-2">
                <dt className="font-medium text-slate-700">{k}</dt>
                <dd className="text-right text-slate-600">{v}</dd>
              </div>
            ))}
          </dl>
        </Section>

        <Section title="Business operations" subtitle="@Observed timers on the service methods">
          <Table head={['Operation', 'Calls', 'Failed', 'Mean']}
                 rows={data.operations.map((o) => [o.name, o.calls, o.failures, `${o.meanMs} ms`])} />
        </Section>

        <Section title="Caches" subtitle="Evicted after commit; errors fall back to the database">
          {data.caches.length === 0 ? <p className="text-sm text-slate-500">No caches.</p> : (
            <div className="space-y-4">
              {data.caches.map((c) => (
                <div key={c.name}>
                  <div className="mb-1 flex justify-between text-sm">
                    <span className="font-medium text-slate-700">{c.name}</span>
                    <span className="text-slate-500">
                      {c.hitRate == null ? 'no reads yet' : `${c.hitRate}% hits`} · {c.hits} hits / {c.misses} misses
                      {c.size != null && ` · ${c.size} entries`}
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
            {([['Pending', outbox.pending, outbox.pending ? 'text-amber-600' : 'text-slate-900'],
               ['Failed', outbox.failed, outbox.failed ? 'text-rose-600' : 'text-slate-900'],
               ['Published (7 days)', outbox.published, 'text-slate-900']] as const).map(([label, value, cls]) => (
              <div key={label} className="rounded-lg bg-slate-50 p-3">
                <p className={`text-2xl font-semibold ${cls}`}>{value}</p>
                <p className="text-xs text-slate-500">{label}</p>
              </div>
            ))}
          </div>
          <p className="mt-3 text-xs text-slate-500">
            Since restart: {outbox.relayedPublished} delivered, {outbox.relayedRetries} retried, {outbox.relayedFailed} gave up.
          </p>
        </Section>

        <Section title="Rate limiting" subtitle="Per client IP, fixed one-minute windows">
          <Table head={['Rule', 'Limit / min', 'Rejected']}
                 rows={Object.entries(rateLimits.limitsPerMinute).map(([rule, limit]) => [rule, limit, rateLimits.rejectedByRule[rule] ?? 0])} />
          {rateLimits.backendErrors > 0 && (
            <p className="mt-2 text-xs text-amber-700">{rateLimits.backendErrors} checks failed open because Redis was unavailable.</p>
          )}
          <div className="mt-4 border-t border-slate-100 pt-4">
            <RateLimitProbe limit={rateLimits.limitsPerMinute.login ?? 10} onDone={reload} />
          </div>
        </Section>

        <Section title="Circuit breakers" subtitle="Stop calling a failing dependency, retry it later">
          <Table head={['Dependency', 'State', 'Failure rate', 'Calls', 'Short-circuited']}
                 rows={data.circuitBreakers.map((b) => [b.name, <BreakerBadge key={b.name} state={b.state} />,
                   b.failureRate == null ? '—' : `${b.failureRate}%`, b.bufferedCalls, b.notPermittedCalls])} />
        </Section>

        <Section title="JVM">
          <div className="mb-1 flex justify-between text-sm">
            <span className="font-medium text-slate-700">Heap</span>
            <span className="text-slate-500">{jvm.heapUsedMb} / {jvm.heapMaxMb} MB · {jvm.threads} threads</span>
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
      <PageHeader title="System"
                  subtitle="Live numbers from this server's metrics registry (the same one /actuator/prometheus exports). Refreshes every 5 seconds." />
      {error && <Alert>{error}</Alert>}
      {!status.data ? (!error && <LoadingBlock />) : <SystemView data={status.data} reload={status.reload} />}
    </div>
  );
}
