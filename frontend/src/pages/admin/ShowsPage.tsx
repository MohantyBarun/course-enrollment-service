import { useEffect, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { adminApi } from '../../api/endpoints';
import type { AdminShowView, CinemaView, MovieDetail, SeatCategory, ShowFormat } from '../../api/types';
import { Alert, Badge, EmptyState, Field, LoadingBlock, Modal, PageHeader, ProgressBar, Spinner } from '../../components/ui';
import { useApi } from '../../hooks/useApi';
import { explainApiError } from '../../lib/backendErrors';
import { addDays, CATEGORY_LABEL, dateParts, dayLabel, formatTime, todayIst } from '../../lib/format';

const DEFAULT_PRICES: Record<SeatCategory, number> = { CLASSIC: 220, PRIME: 320, RECLINER: 550 };

/** "2026-11-02T18:30" typed in IST -> an ISO instant. */
export function istLocalToInstant(local: string): string {
  return new Date(`${local}:00+05:30`).toISOString();
}

function ScheduleForm({ cinema, date, movies, initialMovieId, onClose, onSaved }: {
  cinema: CinemaView; date: string; movies: MovieDetail[]; initialMovieId?: number; onClose: () => void; onSaved: () => void;
}) {
  const [movieId, setMovieId] = useState(
    movies.some((m) => m.id === initialMovieId) ? initialMovieId! : movies[0]?.id ?? 0);
  const [screenId, setScreenId] = useState(cinema.screens[0]?.id ?? 0);
  const [time, setTime] = useState('18:30');
  const [format, setFormat] = useState<ShowFormat>('2D');
  const [prices, setPrices] = useState<Record<SeatCategory, number>>(DEFAULT_PRICES);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const screen = cinema.screens.find((s) => s.id === screenId);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!screen) return;
    setBusy(true);
    setError(null);
    try {
      await adminApi.createShow({
        movieId, screenId, format, startsAt: istLocalToInstant(`${date}T${time}`),
        pricesPaise: Object.fromEntries(screen.categories.map((c) => [c, Math.round(prices[c] * 100)])),
      });
      onSaved();
      onClose();
    } catch (err) {
      setError(err instanceof ApiError
        ? Object.values(err.fieldErrors).length ? Object.values(err.fieldErrors).join(' ') : err.message
        : 'Could not schedule the show.');
      setBusy(false);
    }
  };

  return (
    <Modal title={`Schedule a show at ${cinema.name}`} open onClose={onClose}
           footer={<>
             <button type="button" className="btn-ghost" onClick={onClose}>Cancel</button>
             <button type="submit" form="show-form" className="btn-primary" disabled={busy || !screen || !movieId}>{busy && <Spinner />} Schedule</button>
           </>}>
      <form id="show-form" onSubmit={submit} className="space-y-4" noValidate>
        {error && <Alert>{error}</Alert>}
        <div>
          <label htmlFor="movie" className="label">Movie</label>
          <select id="movie" className="input" value={movieId} onChange={(e) => setMovieId(Number(e.target.value))}>
            {movies.map((m) => <option key={m.id} value={m.id}>{m.title} ({m.durationMinutes} min)</option>)}
          </select>
        </div>
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label htmlFor="screen" className="label">Screen</label>
            <select id="screen" className="input" value={screenId} onChange={(e) => setScreenId(Number(e.target.value))}>
              {cinema.screens.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </div>
          <div>
            <label htmlFor="format" className="label">Format</label>
            <select id="format" className="input" value={format} onChange={(e) => setFormat(e.target.value as ShowFormat)}>
              <option>2D</option><option>3D</option><option>IMAX</option>
            </select>
          </div>
        </div>
        <Field label={`Start time (IST) on ${dateParts(date).day} ${dateParts(date).month}`} type="time" value={time}
               onChange={(e) => setTime(e.target.value)} hint="The screen must be free for the running time plus 15 minutes to clean." />
        {screen && (
          <div>
            <p className="label">Prices (₹)</p>
            <div className="grid grid-cols-3 gap-3">
              {screen.categories.map((c) => (
                <Field key={c} label={CATEGORY_LABEL[c]} type="number" min={1} value={prices[c]}
                       onChange={(e) => setPrices({ ...prices, [c]: Number(e.target.value) })} />
              ))}
            </div>
          </div>
        )}
      </form>
    </Modal>
  );
}

function CancelShowDialog({ show, onClose, onDone }: { show: AdminShowView; onClose: () => void; onDone: (msg: string) => void }) {
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const cancel = async () => {
    setBusy(true);
    setError(null);
    try {
      const r = await adminApi.cancelShow(show.id, reason.trim());
      onDone(`Show cancelled. ${r.bookingsCancelled} booking(s) refunded and emailed.`);
      onClose();
    } catch (err) {
      setError(err instanceof ApiError ? (err.fieldErrors.reason ?? err.message) : 'Could not cancel the show.');
      setBusy(false);
    }
  };
  return (
    <Modal title={`Cancel ${show.movieTitle} at ${formatTime(show.startsAt)}?`} open onClose={onClose}
           footer={<>
             <button type="button" className="btn-ghost" onClick={onClose}>Keep show</button>
             <button type="button" className="btn-danger" onClick={cancel} disabled={busy || !reason.trim()}>{busy && <Spinner />} Cancel show</button>
           </>}>
      <div className="space-y-4">
        <p className="text-sm text-ink-300">All {show.bookedSeats} booked seat(s) are refunded and every customer gets an email with your reason.</p>
        {error && <Alert>{error}</Alert>}
        <Field label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Projector fault" maxLength={200} />
      </div>
    </Modal>
  );
}

export function AdminShowsPage() {
  const cinemas = useApi(adminApi.cinemas);
  const movies = useApi(adminApi.movies);
  // "Schedule a show" on the Movies page links here with ?movie=<id>, which the form preselects
  const [searchParams] = useSearchParams();
  const requestedMovieId = Number(searchParams.get('movie')) || undefined;
  const [cinemaId, setCinemaId] = useState<number | null>(null);
  const [date, setDate] = useState(todayIst());
  const [scheduling, setScheduling] = useState(requestedMovieId !== undefined);
  const [cancelling, setCancelling] = useState<AdminShowView | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    if (cinemaId == null && cinemas.data?.length) setCinemaId(cinemas.data[0].id);
  }, [cinemas.data, cinemaId]);

  const shows = useApi(() => (cinemaId ? adminApi.shows(cinemaId, date) : Promise.resolve([])), [cinemaId, date]);
  const cinema = cinemas.data?.find((c) => c.id === cinemaId);
  const error = explainApiError(cinemas.error ?? shows.error);
  const today = todayIst();
  const days = Array.from({ length: 7 }, (_, i) => addDays(today, i - 1));

  return (
    <div className="space-y-6">
      <PageHeader title="Shows" subtitle="Schedule and cancel shows, one cinema and day at a time."
                  action={cinema && movies.data && (
                    <button type="button" className="btn-primary" onClick={() => setScheduling(true)} disabled={cinema.screens.length === 0}>+ Schedule show</button>
                  )} />
      {error && <Alert>{error}</Alert>}
      {notice && <Alert kind="success">{notice}</Alert>}
      <div className="card flex flex-wrap items-center gap-4 p-4">
        <select aria-label="Cinema" className="input max-w-xs" value={cinemaId ?? ''} onChange={(e) => setCinemaId(Number(e.target.value))}>
          {cinemas.data?.map((c) => <option key={c.id} value={c.id}>{c.cityName}: {c.name}</option>)}
        </select>
        <div className="scrollbar-none flex gap-1.5 overflow-x-auto" role="tablist" aria-label="Day">
          {days.map((d) => (
            <button key={d} type="button" role="tab" aria-selected={d === date} onClick={() => setDate(d)}
                    className={`rounded-xl px-3 py-1.5 text-xs font-bold ${d === date ? 'bg-marquee text-ink-950' : 'bg-white/5 text-ink-300 hover:bg-white/10'}`}>
              {dayLabel(d, today)} {dateParts(d).day}
            </button>
          ))}
        </div>
      </div>

      {!shows.data ? (!error && <LoadingBlock />) : shows.data.length === 0 ? (
        <div className="card"><EmptyState icon="🗓️" title="No shows on this day" /></div>
      ) : (
        <ul className="grid grid-cols-1 gap-3 md:grid-cols-2">
          {shows.data.map((s) => {
            const cancelled = s.status === 'CANCELLED';
            const past = new Date(s.startsAt).getTime() < Date.now();
            return (
              <li key={s.id} className={`card p-4 ${cancelled ? 'opacity-60' : ''}`}>
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="text-lg font-extrabold text-white">{formatTime(s.startsAt)} <span className="text-xs font-semibold text-ink-400">– {formatTime(s.endsAt)}</span></p>
                    <Link to={`/shows/${s.id}`} className="block truncate font-semibold text-sun-300 hover:underline">{s.movieTitle}</Link>
                    <p className="text-xs text-ink-400">{s.screenName} · {s.format}</p>
                  </div>
                  {cancelled ? <Badge tone="flame">Cancelled</Badge>
                    : !past && <button type="button" className="btn-ghost text-xs text-flame-300" onClick={() => setCancelling(s)}>Cancel</button>}
                </div>
                <div className="mt-3">
                  <div className="mb-1 flex justify-between text-xs text-ink-400"><span>Booked</span><span>{s.bookedSeats} / {s.totalSeats}</span></div>
                  <ProgressBar value={s.bookedSeats} max={s.totalSeats} />
                </div>
              </li>
            );
          })}
        </ul>
      )}
      {scheduling && cinema && movies.data && (
        <ScheduleForm cinema={cinema} date={date} movies={movies.data} initialMovieId={requestedMovieId}
                      onClose={() => setScheduling(false)} onSaved={shows.reload} />
      )}
      {cancelling && (
        <CancelShowDialog show={cancelling} onClose={() => setCancelling(null)}
                          onDone={(m) => {
                            setNotice(m);
                            void shows.reload();
                          }} />
      )}
    </div>
  );
}
