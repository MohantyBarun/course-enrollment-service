import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { catalogApi } from '../api/endpoints';
import type { Home, MovieCard } from '../api/types';
import { Poster } from '../components/Poster';
import { Alert, EmptyState, Skeleton } from '../components/ui';
import { useApi } from '../hooks/useApi';
import { useCity } from '../hooks/useCity';
import { explainApiError } from '../lib/backendErrors';
import { formatDate, formatRuntime } from '../lib/format';
import { posterGradient } from '../lib/poster';

function Spotlight({ movie }: { movie: MovieCard }) {
  return (
    <section className="relative overflow-hidden rounded-3xl" aria-label="Featured movie">
      <div className="absolute inset-0 opacity-90" style={{ backgroundImage: posterGradient(movie.title) }} aria-hidden="true" />
      <div className="absolute inset-0 bg-gradient-to-r from-ink-950 via-ink-950/70 to-transparent" aria-hidden="true" />
      <div className="relative flex flex-col gap-6 p-6 sm:flex-row sm:items-center sm:p-10">
        <div className="max-w-xl flex-1 space-y-4 animate-fade-up">
          <span className="chip bg-white/10 text-white">🔥 Trending in theatres</span>
          <h1 className="text-3xl font-extrabold leading-tight tracking-tight text-white sm:text-5xl">{movie.title}</h1>
          <p className="text-sm text-ink-200">
            {movie.certificate === 'UA' ? 'U/A' : movie.certificate} · {movie.language} · {formatRuntime(movie.durationMinutes)} · {movie.genres.join(', ')}
          </p>
          <div className="flex flex-wrap gap-3">
            <Link to={`/movies/${movie.id}`} className="btn-primary">Book tickets</Link>
          </div>
        </div>
        <Poster title={movie.title} posterUrl={movie.posterUrl} certificate={movie.certificate}
                className="hidden aspect-[2/3] w-48 shrink-0 rotate-2 shadow-2xl ring-1 ring-white/20 sm:block" />
      </div>
    </section>
  );
}

function MovieTile({ movie, upcoming }: { movie: MovieCard; upcoming?: boolean }) {
  return (
    <Link to={`/movies/${movie.id}`} className="group block rounded-2xl focus:outline-none focus-visible:ring-2 focus-visible:ring-sun-400">
      <Poster title={movie.title} posterUrl={movie.posterUrl} certificate={movie.certificate}
              className="aspect-[2/3] w-full transition duration-300 group-hover:-translate-y-1 group-hover:shadow-glow" />
      <p className="mt-2.5 truncate text-sm font-bold text-white">{movie.title}</p>
      <p className="truncate text-xs text-ink-400">
        {upcoming ? `Releases ${formatDate(movie.releaseDate)}` : `${movie.language} · ${movie.genres.slice(0, 2).join(', ')}`}
      </p>
    </Link>
  );
}

function HomeSkeleton() {
  return (
    <div className="space-y-10">
      <Skeleton className="h-72 w-full rounded-3xl" />
      <div className="grid grid-cols-2 gap-5 sm:grid-cols-3 lg:grid-cols-5">
        {Array.from({ length: 5 }, (_, i) => <Skeleton key={i} className="aspect-[2/3] w-full" />)}
      </div>
    </div>
  );
}

function Browse({ home }: { home: Home }) {
  const [language, setLanguage] = useState<string | null>(null);
  const [genre, setGenre] = useState<string | null>(null);
  const languages = useMemo(() => [...new Set(home.nowShowing.map((m) => m.language))].sort(), [home]);
  const genres = useMemo(() => [...new Set(home.nowShowing.flatMap((m) => m.genres))].sort(), [home]);
  const shown = home.nowShowing.filter((m) => (!language || m.language === language) && (!genre || m.genres.includes(genre)));

  const chip = (active: boolean) =>
    `shrink-0 rounded-full px-3.5 py-1.5 text-xs font-semibold transition ${active
      ? 'bg-white text-ink-950' : 'bg-white/[0.06] text-ink-200 hover:bg-white/10'}`;

  return (
    <div className="space-y-12">
      {home.nowShowing[0] && <Spotlight movie={home.nowShowing[0]} />}

      <section aria-labelledby="now-showing">
        <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
          <h2 id="now-showing" className="text-xl font-extrabold text-white sm:text-2xl">
            Now showing in <span className="text-gradient">{home.city.name}</span>
          </h2>
        </div>
        <div className="scrollbar-none -mx-4 mb-5 flex gap-2 overflow-x-auto px-4" role="group" aria-label="Filter by language">
          <button type="button" className={chip(!language)} onClick={() => setLanguage(null)} aria-pressed={!language}>All languages</button>
          {languages.map((l) => (
            <button key={l} type="button" className={chip(language === l)} aria-pressed={language === l}
                    onClick={() => setLanguage(language === l ? null : l)}>{l}</button>
          ))}
          <span className="mx-1 w-px shrink-0 bg-white/10" aria-hidden="true" />
          {genres.map((g) => (
            <button key={g} type="button" className={chip(genre === g)} aria-pressed={genre === g}
                    onClick={() => setGenre(genre === g ? null : g)}>{g}</button>
          ))}
        </div>
        {home.nowShowing.length === 0 ? (
          <div className="card"><EmptyState title="No shows in this city yet">Try another city from the top bar.</EmptyState></div>
        ) : shown.length === 0 ? (
          <div className="card"><EmptyState icon="🔎" title="Nothing matches these filters" /></div>
        ) : (
          <div className="grid grid-cols-2 gap-x-5 gap-y-8 sm:grid-cols-3 lg:grid-cols-5">
            {shown.map((m) => <MovieTile key={m.id} movie={m} />)}
          </div>
        )}
      </section>

      {home.comingSoon.length > 0 && (
        <section aria-labelledby="coming-soon" className="rounded-3xl bg-gradient-to-br from-grape-600/25 via-ink-900 to-ink-900 p-6 ring-1 ring-white/5 sm:p-8">
          <h2 id="coming-soon" className="mb-1 text-xl font-extrabold text-white sm:text-2xl">Coming soon ✨</h2>
          <p className="mb-5 text-sm text-ink-400">Mark your calendar.</p>
          <div className="scrollbar-none -mx-2 flex gap-5 overflow-x-auto px-2 pb-2">
            {home.comingSoon.map((m) => (
              <div key={m.id} className="w-36 shrink-0 sm:w-44"><MovieTile movie={m} upcoming /></div>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}

export function HomePage() {
  const { city, loading: citiesLoading, error: citiesError, setPickerOpen } = useCity();
  const home = useApi(() => (city ? catalogApi.home(city.id) : Promise.resolve(null)), [city?.id]);
  const error = explainApiError(citiesError ?? home.error);

  return (
    <div className="bg-aurora">
      <div className="mx-auto max-w-7xl px-4 py-8 sm:px-6">
        {error && <div className="mb-6"><Alert>{error}</Alert></div>}
        {!city && !citiesLoading ? (
          <div className="card mx-auto max-w-lg">
            <EmptyState icon="📍" title="Pick your city to see what's playing">
              <button type="button" className="btn-primary mt-4" onClick={() => setPickerOpen(true)}>Choose city</button>
            </EmptyState>
          </div>
        ) : home.data && home.data.city.id === city?.id ? (
          <Browse home={home.data} />
        ) : (
          !error && <HomeSkeleton />
        )}
      </div>
    </div>
  );
}
