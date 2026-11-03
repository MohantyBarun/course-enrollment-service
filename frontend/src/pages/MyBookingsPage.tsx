import { useState } from 'react';
import { Link } from 'react-router-dom';
import { bookingApi } from '../api/endpoints';
import type { BookingView } from '../api/types';
import { Poster } from '../components/Poster';
import { Alert, Badge, EmptyState, LoadingBlock, PageHeader } from '../components/ui';
import { useApi } from '../hooks/useApi';
import { explainApiError } from '../lib/backendErrors';
import { formatRupees, formatShowTime, pluralize } from '../lib/format';

function BookingRow({ b }: { b: BookingView }) {
  const upcoming = new Date(b.startsAt).getTime() > Date.now();
  return (
    <li>
      <Link to={`/bookings/${b.reference}`}
            className="card flex items-center gap-4 p-4 transition hover:-translate-y-0.5 hover:border-white/15">
        <Poster title={b.movieTitle} posterUrl={b.posterUrl} compact className="h-24 w-16 shrink-0" />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <p className="truncate font-bold text-white">{b.movieTitle}</p>
            {b.status === 'CANCELLED' ? <Badge tone="flame">Cancelled</Badge>
              : upcoming ? <Badge tone="mint">Upcoming</Badge> : <Badge>Watched</Badge>}
          </div>
          <p className="truncate text-sm text-ink-300">{b.cinemaName}</p>
          <p className="text-sm font-semibold text-sun-300">{formatShowTime(b.startsAt)}</p>
          <p className="text-xs text-ink-400">{pluralize(b.seats.length, 'seat')}: {b.seats.map((s) => s.label).join(', ')}</p>
        </div>
        <div className="hidden text-right sm:block">
          <p className="font-extrabold text-white">{formatRupees(b.totalPaise)}</p>
          <p className="font-mono text-xs text-ink-400">{b.reference}</p>
        </div>
      </Link>
    </li>
  );
}

export function MyBookingsPage() {
  const [page, setPage] = useState(0);
  const bookings = useApi(() => bookingApi.mine(page, 10), [page]);
  const data = bookings.data;
  const error = explainApiError(bookings.error);

  return (
    <div className="mx-auto max-w-3xl space-y-6 px-4 py-8 sm:px-6">
      <PageHeader title="My bookings" subtitle="Your tickets, latest show first." />
      {error && <Alert>{error}</Alert>}
      {!data ? (!error && <LoadingBlock />) : data.content.length === 0 ? (
        <div className="card"><EmptyState icon="🎟️" title="No bookings yet">
          <Link to="/" className="btn-primary mt-4">Find a movie</Link>
        </EmptyState></div>
      ) : (
        <>
          <ul className="space-y-3">{data.content.map((b) => <BookingRow key={b.reference} b={b} />)}</ul>
          {data.totalPages > 1 && (
            <div className="flex items-center justify-center gap-3 text-sm">
              <button type="button" className="btn-secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
              <span className="text-ink-400">Page {page + 1} of {data.totalPages}</span>
              <button type="button" className="btn-secondary" disabled={data.last} onClick={() => setPage(page + 1)}>Next</button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
