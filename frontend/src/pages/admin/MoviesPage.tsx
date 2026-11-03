import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { adminApi } from '../../api/endpoints';
import type { AdminMovie, Certificate, MovieDetail, MovieRequest } from '../../api/types';
import { Poster } from '../../components/Poster';
import { Alert, Badge, EmptyState, Field, LoadingBlock, Modal, PageHeader, Spinner } from '../../components/ui';
import { useApi } from '../../hooks/useApi';
import { explainApiError } from '../../lib/backendErrors';
import { formatDate, formatRuntime, todayIst } from '../../lib/format';

const EMPTY: MovieRequest = {
  title: '', synopsis: '', durationMinutes: 120, language: 'Hindi', certificate: 'UA', genres: [], releaseDate: todayIst(), posterUrl: '',
};

function MovieForm({ movie, onClose, onSaved }: { movie: MovieDetail | null; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState<MovieRequest>(movie ? {
    title: movie.title, synopsis: movie.synopsis, durationMinutes: movie.durationMinutes, language: movie.language,
    certificate: movie.certificate, genres: movie.genres, releaseDate: movie.releaseDate, posterUrl: movie.posterUrl ?? '',
  } : EMPTY);
  const [genres, setGenres] = useState((movie?.genres ?? []).join(', '));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setFieldErrors({});
    const body = { ...form, genres: genres.split(',').map((g) => g.trim()).filter(Boolean), posterUrl: form.posterUrl.trim() };
    try {
      if (movie) await adminApi.updateMovie(movie.id, body);
      else await adminApi.createMovie(body);
      onSaved();
      onClose();
    } catch (err) {
      if (err instanceof ApiError) setFieldErrors(err.fieldErrors);
      setError(err instanceof ApiError ? err.message : 'Could not save the movie.');
      setBusy(false);
    }
  };

  return (
    <Modal title={movie ? `Edit ${movie.title}` : 'Add a movie'} open onClose={onClose} wide
           footer={<>
             <button type="button" className="btn-ghost" onClick={onClose}>Cancel</button>
             <button type="submit" form="movie-form" className="btn-primary" disabled={busy}>{busy && <Spinner />} Save movie</button>
           </>}>
      <form id="movie-form" onSubmit={submit} className="grid grid-cols-1 gap-4 sm:grid-cols-[1fr_140px]" noValidate>
        <div className="space-y-4">
          {error && <Alert>{error}</Alert>}
          <Field label="Title" value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} error={fieldErrors.title} />
          <div>
            <label htmlFor="synopsis" className="label">Synopsis</label>
            <textarea id="synopsis" rows={3} className={`input ${fieldErrors.synopsis ? 'input-error' : ''}`} value={form.synopsis}
                      onChange={(e) => setForm({ ...form, synopsis: e.target.value })} />
            {fieldErrors.synopsis && <p className="mt-1 text-xs text-flame-300">{fieldErrors.synopsis}</p>}
          </div>
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-3">
            <Field label="Language" value={form.language} onChange={(e) => setForm({ ...form, language: e.target.value })} error={fieldErrors.language} />
            <div>
              <label htmlFor="cert" className="label">Certificate</label>
              <select id="cert" className="input" value={form.certificate} onChange={(e) => setForm({ ...form, certificate: e.target.value as Certificate })}>
                <option value="U">U</option><option value="UA">U/A</option><option value="A">A</option>
              </select>
            </div>
            <Field label="Minutes" type="number" min={30} max={300} value={form.durationMinutes} disabled={Boolean(movie)}
                   hint={movie ? "Fixed: shows are scheduled around it" : undefined}
                   onChange={(e) => setForm({ ...form, durationMinutes: Number(e.target.value) })} error={fieldErrors.durationMinutes} />
          </div>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Field label="Genres" value={genres} onChange={(e) => setGenres(e.target.value)} hint="Comma separated" error={fieldErrors.genres} />
            <Field label="Release date" type="date" value={form.releaseDate} onChange={(e) => setForm({ ...form, releaseDate: e.target.value })} error={fieldErrors.releaseDate} />
          </div>
          <Field label="Poster URL (optional)" value={form.posterUrl} placeholder="https://…" onChange={(e) => setForm({ ...form, posterUrl: e.target.value })}
                 error={fieldErrors.posterUrl} hint="Leave blank for a generated poster" />
        </div>
        <div className="hidden sm:block">
          <p className="label">Preview</p>
          <Poster title={form.title || 'Untitled'} posterUrl={form.posterUrl || null} certificate={form.certificate} className="aspect-[2/3] w-full" compact />
        </div>
      </form>
    </Modal>
  );
}

/** What customers see of a movie, mirroring the home page rules: shows -> now showing, unreleased -> coming soon. */
export function VisibilityBadge({ movie }: { movie: AdminMovie }) {
  if (movie.visibility === 'NOW_SHOWING') {
    const cities = movie.showingIn.join(', ');
    return (
      <Badge tone="mint" title={`${movie.upcomingShows} upcoming show${movie.upcomingShows === 1 ? '' : 's'} in ${cities}`}>
        Now showing · {movie.showingIn.length === 1 ? cities : `${movie.showingIn.length} cities`}
      </Badge>
    );
  }
  if (movie.visibility === 'COMING_SOON') {
    return <Badge tone="grape" title="Listed under Coming soon in every city until shows are scheduled">Coming soon</Badge>;
  }
  return <Badge tone="flame" title="Released, with no upcoming shows: customers can't find it">Hidden from customers</Badge>;
}

export function AdminMoviesPage() {
  const movies = useApi(adminApi.movies);
  const [editing, setEditing] = useState<MovieDetail | 'new' | null>(null);
  const error = explainApiError(movies.error);

  return (
    <div className="space-y-6">
      <PageHeader title="Movies" subtitle="Everything in the catalogue, newest release first. Customers see a released movie only once it has shows."
                  action={<button type="button" className="btn-primary" onClick={() => setEditing('new')}>+ Add movie</button>} />
      {error && <Alert>{error}</Alert>}
      {!movies.data ? (!error && <LoadingBlock />) : movies.data.length === 0 ? (
        <div className="card"><EmptyState title="No movies yet" /></div>
      ) : (
        <ul className="grid grid-cols-2 gap-5 sm:grid-cols-3 xl:grid-cols-5">
          {movies.data.map((m) => (
            <li key={m.id}>
              <button type="button" onClick={() => setEditing(m)} className="group w-full text-left" aria-label={`Edit ${m.title}`}>
                <Poster title={m.title} posterUrl={m.posterUrl} certificate={m.certificate}
                        className="aspect-[2/3] w-full transition group-hover:-translate-y-1 group-hover:shadow-glow" />
                <p className="mt-2 truncate text-sm font-bold text-white">{m.title}</p>
                <p className="truncate text-xs text-ink-400">{m.language} · {formatRuntime(m.durationMinutes)} · {formatDate(m.releaseDate)}</p>
              </button>
              <div className="mt-1.5 flex flex-wrap items-center gap-2">
                <VisibilityBadge movie={m} />
                {m.visibility !== 'NOW_SHOWING' && (
                  <Link to={`/admin/shows?movie=${m.id}`} className="text-xs font-semibold text-sun-300 hover:underline">Schedule a show</Link>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
      {editing && <MovieForm movie={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onSaved={movies.reload} />}
    </div>
  );
}
