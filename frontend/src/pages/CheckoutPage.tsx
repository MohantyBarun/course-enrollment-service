import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '../api/client';
import { checkoutApi } from '../api/endpoints';
import type { HoldView } from '../api/types';
import { Alert, EmptyState, LoadingBlock, Spinner } from '../components/ui';
import { useApi } from '../hooks/useApi';
import { useCountdown } from '../hooks/useCountdown';
import { usePolling } from '../hooks/usePolling';
import { explainApiError } from '../lib/backendErrors';
import { CATEGORY_LABEL, formatCountdown, formatRupees, formatShowTime } from '../lib/format';
import { posterGradient } from '../lib/poster';

const HOLD_SECONDS = 600;

function CountdownRing({ seconds }: { seconds: number }) {
  const r = 34;
  const c = 2 * Math.PI * r;
  const pct = Math.min(1, seconds / HOLD_SECONDS);
  const urgent = seconds <= 60;
  return (
    <div className="relative h-24 w-24 shrink-0" role="timer" aria-label={`${formatCountdown(seconds)} left to pay`}>
      <svg viewBox="0 0 80 80" className="h-full w-full -rotate-90" aria-hidden="true">
        <circle cx="40" cy="40" r={r} className="fill-none stroke-white/10" strokeWidth="6" />
        <circle cx="40" cy="40" r={r} fill="none" strokeWidth="6" strokeLinecap="round"
                stroke={urgent ? '#ff4f62' : 'url(#ring)'} strokeDasharray={c} strokeDashoffset={c * (1 - pct)}
                className="transition-[stroke-dashoffset] duration-1000 ease-linear" />
        <defs>
          <linearGradient id="ring" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stopColor="#ff4f62" /><stop offset="1" stopColor="#ffc35c" />
          </linearGradient>
        </defs>
      </svg>
      <div className="absolute inset-0 grid place-items-center">
        <span className={`text-lg font-extrabold tabular-nums ${urgent ? 'text-flame-400' : 'text-white'}`}>{formatCountdown(seconds)}</span>
      </div>
    </div>
  );
}

function Summary({ hold }: { hold: HoldView }) {
  return (
    <div className="card overflow-hidden">
      <div className="relative p-6" style={{ backgroundImage: posterGradient(hold.movieTitle) }}>
        <div className="absolute inset-0 bg-gradient-to-r from-ink-950/90 to-ink-950/40" aria-hidden="true" />
        <div className="relative">
          <p className="text-xs font-bold uppercase tracking-widest text-white/70">{hold.format}</p>
          <h2 className="text-2xl font-extrabold text-white">{hold.movieTitle}</h2>
          <p className="text-sm text-white/80">{hold.cinemaName} · {hold.screenName}</p>
          <p className="mt-1 text-sm font-semibold text-sun-300">{formatShowTime(hold.startsAt)}</p>
        </div>
      </div>
      {/* Ticket perforation */}
      <div className="relative h-0 border-t-2 border-dashed border-white/10">
        <span className="absolute -left-3 -top-3 h-6 w-6 rounded-full bg-ink-950" aria-hidden="true" />
        <span className="absolute -right-3 -top-3 h-6 w-6 rounded-full bg-ink-950" aria-hidden="true" />
      </div>
      <div className="space-y-3 p-6 text-sm">
        {hold.seats.map((s) => (
          <div key={s.seatId} className="flex justify-between">
            <span className="text-ink-200"><span className="font-bold text-white">{s.label}</span> · {CATEGORY_LABEL[s.category]}</span>
            <span className="text-ink-200">{formatRupees(s.pricePaise)}</span>
          </div>
        ))}
        <div className="flex justify-between border-t border-white/5 pt-3 text-ink-400">
          <span>Convenience fee</span><span>{formatRupees(hold.convenienceFeePaise)}</span>
        </div>
        <div className="flex justify-between text-base font-extrabold text-white">
          <span>Total</span><span className="text-gradient">{formatRupees(hold.totalPaise)}</span>
        </div>
      </div>
    </div>
  );
}

export function CheckoutPage() {
  const holdId = Number(useParams().holdId);
  const navigate = useNavigate();
  const hold = useApi(() => checkoutApi.get(holdId), [holdId]);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const h = hold.data;
  const secondsLeft = useCountdown(h?.status === 'ACTIVE' ? h.expiresAt : null);
  const waitingForWebhook = h?.latestPayment?.status === 'PENDING' || (h?.status === 'CONVERTED' && !h.bookingReference);

  // The booking appears once the gateway's webhook has been processed
  usePolling(() => waitingForWebhook && hold.reload(), 1500);
  useEffect(() => {
    if (h?.bookingReference) navigate(`/bookings/${h.bookingReference}`, { replace: true, state: { justBooked: true } });
  }, [h?.bookingReference, navigate]);
  // When the timer runs out, fetch the hold so the page shows it expired
  useEffect(() => {
    if (h?.status === 'ACTIVE' && secondsLeft === 0) {
      const t = setTimeout(hold.reload, 1500);
      return () => clearTimeout(t);
    }
  }, [h?.status, secondsLeft, hold.reload]);

  const pay = async () => {
    setBusy(true);
    setActionError(null);
    try {
      const started = await checkoutApi.pay(holdId);
      navigate(`${started.checkoutPath}?hold=${holdId}`);
    } catch (e) {
      setActionError(e instanceof ApiError ? e.message : 'Could not start the payment.');
      setBusy(false);
      void hold.reload();
    }
  };

  const release = async () => {
    setBusy(true);
    try {
      await checkoutApi.release(holdId);
      navigate(`/shows/${h!.showId}`, { replace: true });
    } catch (e) {
      setActionError(e instanceof ApiError ? e.message : 'Could not release the seats.');
      setBusy(false);
    }
  };

  const error = explainApiError(hold.error);
  if (!h) {
    return <div className="mx-auto max-w-3xl px-4 py-10">{error ? <Alert>{error}</Alert> : <LoadingBlock />}</div>;
  }

  const payment = h.latestPayment;
  const ended = h.status === 'EXPIRED' || h.status === 'RELEASED';

  return (
    <div className="bg-aurora">
      <div className="mx-auto grid max-w-5xl gap-6 px-4 py-8 sm:px-6 lg:grid-cols-[1fr_380px]">
        <div className="space-y-6">
          <div>
            <p className="text-xs font-bold uppercase tracking-widest text-ink-400">Checkout</p>
            <h1 className="text-2xl font-extrabold text-white sm:text-3xl">
              {ended ? 'This hold has ended' : waitingForWebhook ? 'Confirming your payment…' : 'Your seats are held 🎉'}
            </h1>
          </div>
          {error && <Alert>{error}</Alert>}
          {actionError && <Alert>{actionError}</Alert>}

          {ended ? (
            <div className="card">
              <EmptyState icon="⌛" title={h.status === 'EXPIRED' ? 'The 10 minutes ran out' : 'You released these seats'}>
                The seats went back on sale.
                <div className="mt-4"><Link to={`/shows/${h.showId}`} className="btn-primary">Pick seats again</Link></div>
              </EmptyState>
            </div>
          ) : waitingForWebhook ? (
            <div className="card flex items-center gap-4 p-6">
              <Spinner className="h-8 w-8 text-sun-400" />
              <div className="text-sm text-ink-300">
                <p className="font-semibold text-white">Waiting for the payment gateway</p>
                <p>The gateway confirms by webhook; your ticket appears here as soon as it lands.</p>
                {payment?.status === 'PENDING' && (
                  <p className="mt-2">Didn't finish paying?{' '}
                    <Link to={`/pay/${payment.gatewayRef}?hold=${holdId}`} className="font-semibold text-sun-300 underline">Back to the payment page</Link>
                  </p>
                )}
              </div>
            </div>
          ) : (
            <div className="card space-y-6 p-6">
              <div className="flex items-center gap-5">
                <CountdownRing seconds={secondsLeft} />
                <div className="text-sm text-ink-300">
                  <p className="font-semibold text-white">Complete payment before the timer runs out</p>
                  <p>Nobody else can take these seats until then. After that they go back on sale.</p>
                  {h.source === 'WAITLIST' && <p className="mt-1 text-grape-400">These seats were held for you from the waitlist.</p>}
                </div>
              </div>
              {payment?.status === 'FAILED' && (
                <Alert>Your last payment didn't go through{payment.failureReason ? `: ${payment.failureReason}` : ''}. Your seats are still held, so try again.</Alert>
              )}
              <div className="flex flex-wrap gap-3">
                <button type="button" className="btn-primary px-8 py-3 text-base" onClick={pay} disabled={busy || secondsLeft === 0}>
                  {busy && <Spinner />} Pay {formatRupees(h.totalPaise)}
                </button>
                <button type="button" className="btn-ghost" onClick={release} disabled={busy}>Release seats</button>
              </div>
            </div>
          )}
        </div>
        <Summary hold={h} />
      </div>
    </div>
  );
}
