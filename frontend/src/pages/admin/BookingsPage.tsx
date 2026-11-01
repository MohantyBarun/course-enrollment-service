import { useState } from 'react';
import { Link } from 'react-router-dom';
import { adminApi } from '../../api/endpoints';
import { Alert, Badge, EmptyState, LoadingBlock, PageHeader } from '../../components/ui';
import { useApi } from '../../hooks/useApi';
import { explainApiError } from '../../lib/backendErrors';
import { formatRupees, formatShowTime, relativeTime } from '../../lib/format';

export function AdminBookingsPage() {
  const [page, setPage] = useState(0);
  const bookings = useApi(() => adminApi.bookings(page, 20), [page]);
  const data = bookings.data;
  const error = explainApiError(bookings.error);

  return (
    <div className="space-y-6">
      <PageHeader title="Bookings" subtitle="Latest bookings across every cinema." />
      {error && <Alert>{error}</Alert>}
      {!data ? (!error && <LoadingBlock />) : data.content.length === 0 ? (
        <div className="card"><EmptyState icon="🎟️" title="No bookings yet" /></div>
      ) : (
        <div className="card overflow-x-auto">
          <table className="w-full min-w-[720px] text-left text-sm">
            <thead className="border-b border-white/5 text-xs uppercase tracking-wider text-ink-400">
              <tr>{['Reference', 'Customer', 'Show', 'Seats', 'Total', 'Status', 'Booked'].map((h) => <th key={h} className="px-4 py-3 font-semibold">{h}</th>)}</tr>
            </thead>
            <tbody className="divide-y divide-white/5">
              {data.content.map((b) => (
                <tr key={b.reference} className="hover:bg-white/[0.03]">
                  <td className="px-4 py-3"><Link to={`/bookings/${b.reference}`} className="font-mono text-xs font-bold text-sun-300 hover:underline">{b.reference}</Link></td>
                  <td className="px-4 py-3"><p className="font-medium text-white">{b.customerName}</p><p className="text-xs text-ink-400">{b.customerEmail}</p></td>
                  <td className="px-4 py-3"><p className="font-medium text-white">{b.movieTitle}</p><p className="text-xs text-ink-400">{b.cinemaName} · {formatShowTime(b.startsAt)}</p></td>
                  <td className="px-4 py-3 text-ink-200">{b.seats}</td>
                  <td className="px-4 py-3 font-semibold text-white">{formatRupees(b.totalPaise)}</td>
                  <td className="px-4 py-3"><Badge tone={b.status === 'CONFIRMED' ? 'mint' : 'flame'}>{b.status.toLowerCase()}</Badge></td>
                  <td className="px-4 py-3 text-xs text-ink-400" title={b.bookedAt}>{relativeTime(b.bookedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {data.totalPages > 1 && (
            <div className="flex items-center justify-end gap-3 border-t border-white/5 px-4 py-3 text-sm">
              <span className="text-ink-400">Page {page + 1} of {data.totalPages}</span>
              <button type="button" className="btn-secondary px-3 py-1.5" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
              <button type="button" className="btn-secondary px-3 py-1.5" disabled={data.last} onClick={() => setPage(page + 1)}>Next</button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
