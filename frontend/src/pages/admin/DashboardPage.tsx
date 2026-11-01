import { useState } from 'react';
import { Link } from 'react-router-dom';
import { adminApi } from '../../api/endpoints';
import type { Dashboard } from '../../api/types';
import { Alert, Badge, EmptyState, LoadingBlock, PageHeader, ProgressBar, StatCard } from '../../components/ui';
import { useApi } from '../../hooks/useApi';
import { usePolling } from '../../hooks/usePolling';
import { explainApiError } from '../../lib/backendErrors';
import { dateParts, formatRupees, formatShowTime, relativeTime } from '../../lib/format';

function RevenueChart({ days }: { days: Dashboard['last7Days'] }) {
  const [metric, setMetric] = useState<'revenuePaise' | 'tickets'>('revenuePaise');
  const max = Math.max(1, ...days.map((d) => d[metric]));
  const fmt = (v: number) => (metric === 'revenuePaise' ? formatRupees(v) : String(v));
  return (
    <section className="card p-5">
      <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
        <h2 className="font-bold text-white">Last 7 days</h2>
        <div className="flex rounded-full bg-ink-900 p-1 text-xs font-semibold" role="group" aria-label="Metric">
          {([['revenuePaise', 'Revenue'], ['tickets', 'Tickets']] as const).map(([k, label]) => (
            <button key={k} type="button" aria-pressed={metric === k} onClick={() => setMetric(k)}
                    className={`rounded-full px-3 py-1 ${metric === k ? 'bg-white text-ink-950' : 'text-ink-300'}`}>{label}</button>
          ))}
        </div>
      </div>
      <div className="flex h-48 items-end gap-3" role="list" aria-label={`${metric === 'tickets' ? 'Tickets' : 'Revenue'} per day`}>
        {days.map((d) => {
          const v = d[metric];
          const { weekday, day } = dateParts(d.date);
          return (
            <div key={d.date} role="listitem" className="group flex h-full flex-1 flex-col items-center justify-end gap-2"
                 aria-label={`${weekday} ${day}: ${fmt(v)}`}>
              <span className="text-[10px] font-semibold text-ink-300 opacity-0 transition group-hover:opacity-100">{fmt(v)}</span>
              <div className="w-full max-w-[3rem] rounded-t-xl bg-gradient-to-t from-flame-500 via-flame-400 to-sun-400 transition-all group-hover:brightness-125"
                   style={{ height: `${Math.max(2, (v / max) * 100)}%` }} />
              <span className="text-[10px] font-bold uppercase tracking-wider text-ink-400">{weekday} {day}</span>
            </div>
          );
        })}
      </div>
    </section>
  );
}

export function DashboardPage() {
  const dash = useApi(adminApi.dashboard);
  usePolling(dash.reload, 15_000);
  const d = dash.data;
  const error = explainApiError(dash.error);

  return (
    <div className="space-y-6">
      <PageHeader title="Dashboard" subtitle={d ? `Today in IST · updated ${relativeTime(d.generatedAt)}` : 'Today in IST'} />
      {error && <Alert>{error}</Alert>}
      {!d ? (!error && <LoadingBlock />) : (
        <>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <StatCard label="Revenue today" value={formatRupees(d.revenueTodayPaise)} />
            <StatCard label="Bookings today" value={d.bookingsToday} accent="from-grape-500 to-flame-500" />
            <StatCard label="Tickets today" value={d.ticketsToday} accent="from-mint-400 to-grape-500" />
          </div>
          <RevenueChart days={d.last7Days} />
          <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
            <section className="card p-5">
              <h2 className="mb-4 font-bold text-white">Top movies <span className="text-xs font-normal text-ink-400">(last 7 days)</span></h2>
              {d.topMovies.length === 0 ? <EmptyState title="No sales yet" /> : (
                <ol className="space-y-3">
                  {d.topMovies.map((m, i) => (
                    <li key={m.title} className="flex items-center gap-3">
                      <span className={`grid h-8 w-8 shrink-0 place-items-center rounded-xl text-sm font-extrabold ${i === 0 ? 'bg-marquee text-ink-950' : 'bg-white/5 text-ink-300'}`}>{i + 1}</span>
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-semibold text-white">{m.title}</p>
                        <ProgressBar value={m.tickets} max={d.topMovies[0].tickets} />
                      </div>
                      <div className="text-right text-xs">
                        <p className="font-bold text-white">{formatRupees(m.revenuePaise)}</p>
                        <p className="text-ink-400">{m.tickets} tickets</p>
                      </div>
                    </li>
                  ))}
                </ol>
              )}
            </section>
            <section className="card p-5">
              <h2 className="mb-4 font-bold text-white">Busiest upcoming shows</h2>
              {d.busiestUpcomingShows.length === 0 ? <EmptyState title="No upcoming shows" /> : (
                <ul className="space-y-3">
                  {d.busiestUpcomingShows.map((s) => {
                    const pct = s.total ? Math.round((s.booked / s.total) * 100) : 0;
                    return (
                      <li key={s.showId}>
                        <Link to={`/shows/${s.showId}`} className="block rounded-xl p-2 hover:bg-white/5">
                          <div className="mb-1.5 flex items-center justify-between gap-2">
                            <div className="min-w-0">
                              <p className="truncate text-sm font-semibold text-white">{s.movieTitle}</p>
                              <p className="truncate text-xs text-ink-400">{s.cinemaName} · {formatShowTime(s.startsAt)}</p>
                            </div>
                            <Badge tone={pct >= 90 ? 'flame' : pct >= 60 ? 'sun' : 'mint'}>{pct}%</Badge>
                          </div>
                          <ProgressBar value={s.booked} max={s.total} tone={pct >= 90 ? 'bad' : 'brand'} />
                        </Link>
                      </li>
                    );
                  })}
                </ul>
              )}
            </section>
          </div>
        </>
      )}
    </div>
  );
}
