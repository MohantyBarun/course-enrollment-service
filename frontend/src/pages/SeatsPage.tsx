import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '../api/client';
import { catalogApi, checkoutApi } from '../api/endpoints';
import type { SeatMap, SeatView, ShowDetail } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Poster } from '../components/Poster';
import { SeatGrid, SeatLegend } from '../components/SeatGrid';
import { Alert, LoadingBlock, Spinner } from '../components/ui';
import { useApi } from '../hooks/useApi';
import { useLiveSeatMap } from '../hooks/useLiveSeatMap';
import { explainApiError } from '../lib/backendErrors';
import { CATEGORY_LABEL, formatRupees, formatShowTime, pluralize } from '../lib/format';
import { indexSeats, priceOf, takenFromSelection, type SeatInfo } from '../lib/seatMap';

const MAX_SEATS = 10;

function LiveBadge({ live }: { live: boolean }) {
  return (
    <span className={`chip ${live ? 'bg-mint-400/15 text-mint-300' : 'text-ink-400'}`}
          title={live ? 'Seats update the moment someone holds or books one' : 'Reconnecting to live updates…'}>
      <span className={`h-2 w-2 rounded-full ${live ? 'bg-mint-400 animate-pulse' : 'bg-ink-500'}`} aria-hidden="true" />
      {live ? 'Live' : 'Connecting'}
    </span>
  );
}

function WaitlistPanel({ showId }: { showId: number }) {
  const { session } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [seats, setSeats] = useState(2);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const join = async () => {
    if (!session) {
      navigate('/login', { state: { from: location.pathname } });
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await checkoutApi.joinWaitlist(showId, seats);
      navigate('/waitlist', { state: { joined: true } });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not join the waitlist.');
      setBusy(false);
    }
  };

  return (
    <div className="card relative overflow-hidden p-6">
      <div className="absolute -right-10 -top-10 h-40 w-40 rounded-full bg-grape-500/30 blur-3xl" aria-hidden="true" />
      <h2 className="text-lg font-extrabold text-white">Sold out. Join the waitlist? 🙌</h2>
      <p className="mt-1 max-w-xl text-sm text-ink-300">
        When seats free up (a cancellation or an unpaid hold), we hold them for the first person in line whose
        request fits and email you. You then have 10 minutes to pay.
      </p>
      {error && <div className="mt-4"><Alert>{error}</Alert></div>}
      <div className="mt-5 flex flex-wrap items-center gap-4">
        <div className="flex items-center gap-1 rounded-full bg-ink-900 p-1" role="group" aria-label="Seats wanted">
          {[1, 2, 3, 4, 5, 6].map((n) => (
            <button key={n} type="button" aria-pressed={seats === n} onClick={() => setSeats(n)}
                    className={`h-9 w-9 rounded-full text-sm font-bold transition ${seats === n ? 'bg-marquee text-ink-950' : 'text-ink-300 hover:bg-white/5'}`}>
              {n}
            </button>
          ))}
        </div>
        <button type="button" className="btn-primary" onClick={join} disabled={busy || session?.role === 'ROLE_ADMIN'}>
          {busy && <Spinner />} Join waitlist for {pluralize(seats, 'seat')}
        </button>
      </div>
    </div>
  );
}

function ShowHeader({ show, live }: { show: ShowDetail; live: boolean }) {
  return (
    <div className="flex items-center gap-4">
      <Poster title={show.movie.title} posterUrl={show.movie.posterUrl} compact className="hidden h-20 w-14 shrink-0 sm:block" />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <Link to={`/movies/${show.movie.id}`} className="truncate text-xl font-extrabold text-white hover:underline sm:text-2xl">
            {show.movie.title}
          </Link>
          <span className="chip">{show.format}</span>
          <LiveBadge live={live} />
        </div>
        <p className="truncate text-sm text-ink-300">{show.cinema.name} · {show.screenName}</p>
        <p className="text-sm font-semibold text-sun-300">{formatShowTime(show.startsAt)}</p>
      </div>
    </div>
  );
}

export function SeatsPage() {
  const showId = Number(useParams().showId);
  const navigate = useNavigate();
  const location = useLocation();
  const { session } = useAuth();
  const show = useApi(() => catalogApi.show(showId), [showId]);
  const { map, error: mapError, live, reload } = useLiveSeatMap(showId);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [notice, setNotice] = useState<string | null>(null);
  const [holdError, setHoldError] = useState<string | null>(null);
  const [holding, setHolding] = useState(false);
  const [flashing, setFlashing] = useState<Set<number>>(new Set());
  const prevMap = useRef<SeatMap | null>(null);

  const index = useMemo(() => (map ? indexSeats(map) : new Map<number, SeatInfo>()), [map]);

  // Live updates: drop selected seats someone else just took, and flash every seat that changed
  useEffect(() => {
    if (!map) return;
    const before = prevMap.current;
    prevMap.current = map;
    if (!before || before === map) return;
    const oldStatus = new Map(before.rows.flatMap((r) => r.seats.map((s) => [s.id, s.status] as const)));
    const changed = map.rows.flatMap((r) => r.seats).filter((s) => oldStatus.get(s.id) !== s.status).map((s) => s.id);
    if (changed.length && changed.length < 40) {
      setFlashing(new Set(changed));
      const t = setTimeout(() => setFlashing(new Set()), 1800);
      return () => clearTimeout(t);
    }
  }, [map]);

  useEffect(() => {
    // While our own hold is in flight, its seats turning "held" is us, not someone else
    if (!map || selected.size === 0 || holding) return;
    const taken = takenFromSelection(map, selected);
    if (taken.length === 0) return;
    setSelected((s) => new Set([...s].filter((id) => !taken.includes(id))));
    const labels = taken.map((id) => index.get(id)?.label ?? '').filter(Boolean).join(', ');
    setNotice(`Someone just grabbed ${labels}. Pick another seat.`);
  }, [map, selected, index, holding]);

  const toggle = useCallback((seat: SeatView) => {
    setNotice(null);
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(seat.id)) next.delete(seat.id);
      else if (next.size >= MAX_SEATS) {
        setNotice(`You can book up to ${MAX_SEATS} seats at a time.`);
        return current;
      } else next.add(seat.id);
      return next;
    });
  }, []);

  const picked = [...selected].map((id) => index.get(id)).filter((p): p is SeatInfo => Boolean(p));
  const total = map ? picked.reduce((sum, p) => sum + priceOf(map.prices, p.category), 0) : 0;

  const hold = async () => {
    if (!session) {
      navigate('/login', { state: { from: location.pathname } });
      return;
    }
    setHolding(true);
    setHoldError(null);
    try {
      const h = await checkoutApi.hold(showId, [...selected]);
      navigate(`/checkout/${h.holdId}`);
    } catch (e) {
      setHolding(false);
      if (e instanceof ApiError && e.status === 409 && Array.isArray(e.body.unavailableSeatIds)) {
        const taken = e.body.unavailableSeatIds as number[];
        setSelected((s) => new Set([...s].filter((id) => !taken.includes(id))));
        void reload();
      }
      setHoldError(e instanceof ApiError ? e.message : 'Could not hold these seats.');
    }
  };

  const error = explainApiError(show.error ?? mapError);
  const soldOut = map && map.bookable && map.available === 0;

  return (
    <div className="bg-aurora">
      <div className="mx-auto max-w-6xl space-y-6 px-4 py-6 pb-36 sm:px-6">
        {show.data && <ShowHeader show={show.data} live={live} />}
        {error && <Alert>{error}</Alert>}
        {show.data && !show.data.bookable && (
          <Alert kind="warn">
            {show.data.status === 'CANCELLED' ? 'This show was cancelled.' : 'Booking for this show has closed.'}{' '}
            <Link to={`/movies/${show.data.movie.id}`} className="font-semibold underline">See other showtimes</Link>
          </Alert>
        )}
        {soldOut && <WaitlistPanel showId={showId} />}
        {notice && <Alert kind="warn">{notice}</Alert>}

        {!map ? (!mapError && <LoadingBlock label="Loading the seat map…" />) : (
          <div className="card space-y-6 p-4 sm:p-8">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <p className="text-sm text-ink-300">
                <span className="font-bold text-mint-300">{map.available}</span> of {map.total} seats available
              </p>
              <SeatLegend />
            </div>
            <SeatGrid map={map} selected={selected} onToggle={toggle} flashing={flashing} />
          </div>
        )}
      </div>

      {selected.size > 0 && map && (
        <div className="fixed inset-x-0 bottom-0 z-30 border-t border-white/10 bg-ink-900/95 backdrop-blur-xl animate-fade-up">
          <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-4 px-4 py-4 sm:px-6">
            <div className="min-w-0 flex-1">
              <p className="text-xs font-semibold uppercase tracking-wider text-ink-400">
                {pluralize(selected.size, 'seat')} · {[...new Set(picked.map((p) => CATEGORY_LABEL[p.category]))].join(', ')}
              </p>
              <p className="truncate text-sm font-bold text-white">{picked.map((p) => p.label).join(', ')}</p>
              {holdError && <p className="mt-1 text-xs text-flame-300" role="alert">{holdError}</p>}
              {session?.role === 'ROLE_ADMIN' && (
                <p className="mt-1 text-xs text-sun-300">Admin accounts can't book. Sign in as a customer to try checkout.</p>
              )}
            </div>
            <div className="text-right">
              <p className="text-xs text-ink-400">Tickets</p>
              <p className="text-lg font-extrabold text-white">{formatRupees(total)}</p>
            </div>
            <button type="button" className="btn-primary px-7 py-3" onClick={hold}
                    disabled={holding || session?.role === 'ROLE_ADMIN'}>
              {holding && <Spinner />} {session ? 'Hold seats & pay' : 'Sign in to book'}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
