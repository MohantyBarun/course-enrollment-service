import { Link, useParams, useSearchParams } from 'react-router-dom';
import { catalogApi } from '../api/endpoints';
import type { Availability, MovieDetail, ShowtimeView, Showtimes } from '../api/types';
import { Poster } from '../components/Poster';
import { Alert, EmptyState, LoadingBlock, Skeleton } from '../components/ui';
import { useApi } from '../hooks/useApi';
import { useCity } from '../hooks/useCity';
import { explainApiError } from '../lib/backendErrors';
import { dateParts, dayLabel, formatDate, formatRupees, formatRuntime, formatTime, todayIst } from '../lib/format';
import { posterGradient } from '../lib/poster';

const AVAILABILITY: Record<Availability, { label: string; pill: string; dot: string }> = {
  AVAILABLE: { label: 'Available', pill: 'border-mint-400/40 text-mint-300 hover:bg-mint-400/10', dot: 'bg-mint-400' },
  FILLING_FAST: { label: 'Filling fast', pill: 'border-sun-400/50 text-sun-300 hover:bg-sun-400/10', dot: 'bg-sun-400' },
  SOLD_OUT: { label: 'Sold out', pill: 'border-white/10 text-ink-500 hover:bg-white/5', dot: 'bg-ink-500' },
};

function MovieHero({ movie }: { movie: MovieDetail }) {
  const released = movie.releaseDate <= todayIst();
  return (
    <section className="relative overflow-hidden">
      <div className="absolute inset-0 scale-110 opacity-60 blur-2xl" style={{ backgroundImage: posterGradient(movie.title) }} aria-hidden="true" />
      <div className="absolute inset-0 bg-gradient-to-b from-ink-950/40 via-ink-950/80 to-ink-950" aria-hidden="true" />
      <div className="relative mx-auto flex max-w-7xl flex-col gap-6 px-4 py-10 sm:flex-row sm:px-6">
        <Poster title={movie.title} posterUrl={movie.posterUrl} certificate={movie.certificate}
                className="aspect-[2/3] w-40 shrink-0 shadow-2xl ring-1 ring-white/15 sm:w-56" />
        <div className="max-w-2xl space-y-4 animate-fade-up">
          <h1 className="text-3xl font-extrabold tracking-tight text-white sm:text-5xl">{movie.title}</h1>
          <div className="flex flex-wrap gap-2">
            <span className="chip">{movie.certificate === 'UA' ? 'U/A' : movie.certificate}</span>
            <span className="chip">{movie.language}</span>
            <span className="chip">⏱ {formatRuntime(movie.durationMinutes)}</span>
            {movie.genres.map((g) => <span key={g} className="chip bg-grape-500/20 text-grape-400">{g}</span>)}
          </div>
          <p className="text-sm leading-relaxed text-ink-200">{movie.synopsis}</p>
          <p className="text-xs text-ink-400">{released ? 'Released' : 'Releases'} {formatDate(movie.releaseDate)}</p>
        </div>
      </div>
    </section>
  );
}

function DateStrip({ dates, selected, onSelect }: { dates: string[]; selected: string | null; onSelect: (d: string) => void }) {
  const today = todayIst();
  return (
    <div className="scrollbar-none -mx-4 flex gap-2 overflow-x-auto px-4 py-1" role="tablist" aria-label="Show date">
      {dates.map((d) => {
        const { day, month } = dateParts(d);
        const active = d === selected;
        return (
          <button key={d} type="button" role="tab" aria-selected={active} onClick={() => onSelect(d)}
                  className={`flex w-16 shrink-0 flex-col items-center rounded-2xl py-2 transition ${active
                    ? 'bg-marquee text-ink-950 shadow-glow' : 'bg-white/[0.05] text-ink-200 hover:bg-white/10'}`}>
            <span className="text-[10px] font-bold tracking-wider opacity-80">{dayLabel(d, today)}</span>
            <span className="text-xl font-extrabold leading-tight">{day}</span>
            <span className="text-[10px] font-bold tracking-wider opacity-80">{month}</span>
          </button>
        );
      })}
    </div>
  );
}

function ShowtimePill({ show }: { show: ShowtimeView }) {
  const a = AVAILABILITY[show.availability];
  const price = show.minPricePaise === show.maxPricePaise ? formatRupees(show.minPricePaise)
    : `${formatRupees(show.minPricePaise)}–${formatRupees(show.maxPricePaise)}`;
  return (
    <Link to={`/shows/${show.showId}`}
          title={`${a.label}${show.availability === 'SOLD_OUT' ? ': join the waitlist' : ` · ${show.seatsLeft} seats left`} · ${price}`}
          className={`group flex flex-col items-center rounded-xl border bg-ink-900/60 px-4 py-2 transition ${a.pill}`}>
      <span className="text-sm font-bold">{formatTime(show.startsAt)}</span>
      <span className="flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wider text-ink-400">
        <span className={`h-1.5 w-1.5 rounded-full ${a.dot}`} aria-hidden="true" />
        {show.format}{show.availability === 'SOLD_OUT' ? ' · Sold out' : ''}
      </span>
    </Link>
  );
}

function ShowtimeList({ data }: { data: Showtimes }) {
  if (data.cinemas.length === 0) {
    return <div className="card"><EmptyState icon="🗓️" title="No shows on this day">Pick another date above.</EmptyState></div>;
  }
  return (
    <ul className="space-y-4">
      {data.cinemas.map((c) => (
        <li key={c.cinemaId} className="card p-5">
          <div className="mb-4 flex flex-wrap items-start justify-between gap-2">
            <div>
              <h3 className="font-bold text-white">{c.cinemaName}</h3>
              <p className="text-xs text-ink-400">{c.address}</p>
            </div>
          </div>
          <div className="flex flex-wrap gap-3">
            {c.shows.map((s) => <ShowtimePill key={s.showId} show={s} />)}
          </div>
        </li>
      ))}
    </ul>
  );
}

export function MoviePage() {
  const movieId = Number(useParams().movieId);
  const [params, setParams] = useSearchParams();
  const date = params.get('date') ?? undefined;
  const { city, setPickerOpen } = useCity();

  const movie = useApi(() => catalogApi.movie(movieId), [movieId]);
  const showtimes = useApi(() => (city ? catalogApi.showtimes(movieId, city.id, date) : Promise.resolve(null)),
    [movieId, city?.id, date]);
  const error = explainApiError(movie.error ?? showtimes.error);

  return (
    <div>
      {movie.data ? <MovieHero movie={movie.data} /> : !movie.error && <Skeleton className="mx-auto mt-8 h-72 max-w-7xl" />}
      <div className="mx-auto max-w-7xl space-y-6 px-4 pb-12 sm:px-6">
        {error && <Alert>{error}</Alert>}
        {movie.data && (
          <section aria-labelledby="showtimes" className="space-y-5">
            <div className="flex flex-wrap items-end justify-between gap-3">
              <h2 id="showtimes" className="text-xl font-extrabold text-white">
                Showtimes {city && <>in <button type="button" onClick={() => setPickerOpen(true)} className="text-gradient underline decoration-sun-400/40 underline-offset-4">{city.name}</button></>}
              </h2>
              <div className="flex flex-wrap gap-3 text-xs text-ink-400">
                {(['AVAILABLE', 'FILLING_FAST', 'SOLD_OUT'] as Availability[]).map((a) => (
                  <span key={a} className="flex items-center gap-1.5">
                    <span className={`h-2 w-2 rounded-full ${AVAILABILITY[a].dot}`} aria-hidden="true" />{AVAILABILITY[a].label}
                  </span>
                ))}
              </div>
            </div>
            {!city ? (
              <div className="card"><EmptyState icon="📍" title="Choose a city to see showtimes">
                <button type="button" className="btn-primary mt-4" onClick={() => setPickerOpen(true)}>Choose city</button>
              </EmptyState></div>
            ) : !showtimes.data ? (
              !showtimes.error && <LoadingBlock label="Finding shows…" />
            ) : showtimes.data.availableDates.length === 0 ? (
              <div className="card"><EmptyState icon="🍿" title={`Not playing in ${city.name} right now`}>
                Check back soon, or try another city.
              </EmptyState></div>
            ) : (
              <>
                <DateStrip dates={showtimes.data.availableDates} selected={showtimes.data.date}
                           onSelect={(d) => setParams({ date: d }, { replace: true })} />
                <ShowtimeList data={showtimes.data} />
              </>
            )}
          </section>
        )}
      </div>
    </div>
  );
}
