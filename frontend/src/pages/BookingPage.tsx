import { useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { ApiError } from '../api/client';
import { bookingApi } from '../api/endpoints';
import type { BookingView } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Poster } from '../components/Poster';
import { Alert, Badge, LoadingBlock, Modal, Spinner } from '../components/ui';
import { useApi } from '../hooks/useApi';
import { explainApiError } from '../lib/backendErrors';
import { CATEGORY_LABEL, formatRupees, formatShowTime, relativeTime } from '../lib/format';
import { hashString, posterGradient } from '../lib/poster';

/** A decorative barcode generated from the booking reference (not machine-readable). */
function Barcode({ text }: { text: string }) {
  let h = hashString(text);
  const bars = Array.from({ length: 42 }, () => {
    h = Math.imul(h ^ (h >>> 15), 2246822507) >>> 0;
    return 1 + (h % 3);
  });
  return (
    <div className="flex h-14 items-stretch justify-center gap-[2px]" aria-hidden="true">
      {bars.map((w, i) => <span key={i} className={i % 2 ? 'bg-transparent' : 'bg-ink-100'} style={{ width: w * 1.5 }} />)}
    </div>
  );
}

export function CancelBookingDialog({ booking, open, onClose, onCancelled }: {
  booking: BookingView; open: boolean; onClose: () => void; onCancelled: (b: BookingView) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const cancel = async () => {
    setBusy(true);
    setError(null);
    try {
      onCancelled(await bookingApi.cancel(booking.reference));
      onClose();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not cancel the booking.');
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal title="Cancel this booking?" open={open} onClose={onClose}
           footer={<>
             <button type="button" className="btn-ghost" onClick={onClose}>Keep it</button>
             <button type="button" className="btn-danger" onClick={cancel} disabled={busy}>{busy && <Spinner />} Cancel booking</button>
           </>}>
      <p className="text-sm text-ink-300">
        {booking.movieTitle}, {formatShowTime(booking.startsAt)}. Your {booking.seats.length} seat(s) go back on sale and
        {' '}{formatRupees(booking.totalPaise)} is refunded.
      </p>
      {error && <div className="mt-4"><Alert>{error}</Alert></div>}
    </Modal>
  );
}

export function BookingPage() {
  const reference = useParams().reference!;
  const justBooked = Boolean((useLocation().state as { justBooked?: boolean } | null)?.justBooked);
  const { isAdmin } = useAuth();
  const booking = useApi(() => bookingApi.get(reference), [reference]);
  const [confirming, setConfirming] = useState(false);
  const b = booking.data;
  const error = explainApiError(booking.error);

  if (!b) return <div className="mx-auto max-w-xl px-4 py-10">{error ? <Alert>{error}</Alert> : <LoadingBlock />}</div>;
  const cancelled = b.status === 'CANCELLED';

  return (
    <div className="bg-aurora">
      <div className="mx-auto max-w-xl space-y-6 px-4 py-10">
        {justBooked && (
          <div className="text-center animate-fade-up">
            <p className="text-5xl" aria-hidden="true">🎉</p>
            <h1 className="mt-2 text-3xl font-extrabold text-white">You're going to the movies!</h1>
            <p className="text-sm text-ink-300">Your ticket is confirmed and on its way to your inbox.</p>
          </div>
        )}

        <article className={`card overflow-hidden ${cancelled ? 'opacity-70' : ''}`} aria-label="Ticket">
          <div className="relative flex gap-4 p-5" style={{ backgroundImage: posterGradient(b.movieTitle) }}>
            <div className="absolute inset-0 bg-gradient-to-r from-ink-950/90 via-ink-950/60 to-ink-950/30" aria-hidden="true" />
            <Poster title={b.movieTitle} posterUrl={b.posterUrl} compact className="relative h-28 w-20 shrink-0 ring-1 ring-white/20" />
            <div className="relative min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <Badge tone={cancelled ? 'flame' : 'mint'}>{cancelled ? 'Cancelled' : 'Confirmed'}</Badge>
                <span className="chip bg-black/30 text-white">{b.format}</span>
              </div>
              <h2 className="mt-2 text-xl font-extrabold text-white">{b.movieTitle}</h2>
              <p className="text-sm text-white/80">{b.cinemaName}, {b.cityName}</p>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-4 p-5 text-sm">
            <div><p className="text-xs uppercase tracking-wider text-ink-400">When</p><p className="font-bold text-white">{formatShowTime(b.startsAt)}</p></div>
            <div><p className="text-xs uppercase tracking-wider text-ink-400">Screen</p><p className="font-bold text-white">{b.screenName}</p></div>
            <div className="col-span-2">
              <p className="text-xs uppercase tracking-wider text-ink-400">Seats</p>
              <div className="mt-1 flex flex-wrap gap-1.5">
                {b.seats.map((s) => (
                  <span key={s.label} className="rounded-lg bg-white/[0.06] px-2 py-1 text-xs font-bold text-white" title={CATEGORY_LABEL[s.category]}>
                    {s.label} <span className="font-medium text-ink-400">{CATEGORY_LABEL[s.category]}</span>
                  </span>
                ))}
              </div>
            </div>
          </div>
          <div className="relative h-0 border-t-2 border-dashed border-white/10">
            <span className="absolute -left-3 -top-3 h-6 w-6 rounded-full bg-ink-950" aria-hidden="true" />
            <span className="absolute -right-3 -top-3 h-6 w-6 rounded-full bg-ink-950" aria-hidden="true" />
          </div>
          <div className="space-y-4 p-5">
            {!cancelled && <Barcode text={b.reference} />}
            <p className="text-center font-mono text-lg font-bold tracking-[0.3em] text-white">{b.reference}</p>
            <div className="space-y-1 text-sm">
              <div className="flex justify-between text-ink-300"><span>Tickets</span><span>{formatRupees(b.ticketsPaise)}</span></div>
              <div className="flex justify-between text-ink-300"><span>Convenience fee</span><span>{formatRupees(b.convenienceFeePaise)}</span></div>
              <div className="flex justify-between font-extrabold text-white"><span>{cancelled ? 'Refunded' : 'Paid'}</span><span>{formatRupees(b.totalPaise)}</span></div>
            </div>
            {cancelled && (
              <p className="text-xs text-ink-400">Cancelled {relativeTime(b.cancelledAt)}{b.cancelReason ? `: ${b.cancelReason}` : ''}</p>
            )}
          </div>
        </article>

        <div className="flex flex-wrap justify-center gap-3">
          {!isAdmin && <Link to="/bookings" className="btn-secondary">All my bookings</Link>}
          {b.cancellable && <button type="button" className="btn-danger" onClick={() => setConfirming(true)}>Cancel booking</button>}
        </div>
        {b.status === 'CONFIRMED' && !b.cancellable && !isAdmin && (
          <p className="text-center text-xs text-ink-500">Bookings can be cancelled up to 2 hours before the show.</p>
        )}
        <CancelBookingDialog booking={b} open={confirming} onClose={() => setConfirming(false)} onCancelled={booking.setData} />
      </div>
    </div>
  );
}
