import { useMemo, useState, type FormEvent } from 'react';
import { ApiError } from '../../api/client';
import { adminApi } from '../../api/endpoints';
import type { CinemaView, RowSpec, SeatCategory } from '../../api/types';
import { Alert, Badge, EmptyState, Field, LoadingBlock, Modal, PageHeader, Spinner } from '../../components/ui';
import { useApi } from '../../hooks/useApi';
import { explainApiError } from '../../lib/backendErrors';
import { CATEGORY_LABEL } from '../../lib/format';

const CATEGORY_TONE: Record<SeatCategory, string> = {
  CLASSIC: 'border-mint-400/60',
  PRIME: 'border-grape-400/70',
  RECLINER: 'border-sun-400/80 bg-sun-400/10',
};

function rowLabel(i: number): string {
  // A..Z, skipping I (it reads like 1), then AA, AB…
  const letters = 'ABCDEFGHJKLMNOPQRSTUVWXYZ';
  return i < letters.length ? letters[i] : `A${letters[i - letters.length]}`;
}

const PRESET: RowSpec[] = [
  ...'ABCDEF'.split('').map((label) => ({ label, category: 'CLASSIC' as const, seats: 14, aisleAfter: [7] })),
  ...'GH'.split('').map((label) => ({ label, category: 'PRIME' as const, seats: 14, aisleAfter: [7] })),
  { label: 'J', category: 'RECLINER', seats: 6, aisleAfter: [3] },
];

/** The rows as the hall looks: back row at the top, the screen at the bottom. */
function LayoutPreview({ rows }: { rows: RowSpec[] }) {
  return (
    <div className="scrollbar-none overflow-x-auto rounded-xl bg-ink-950 p-4">
      <div className="mx-auto w-max space-y-1">
        {[...rows].reverse().map((r, i) => (
          <div key={`${r.label}-${i}`} className="flex items-center gap-2">
            <span className="w-5 text-right text-[10px] text-ink-500">{r.label}</span>
            <div className="flex gap-0.5">
              {Array.from({ length: r.seats }, (_, n) => (
                <span key={n} className={`h-2.5 w-2.5 rounded-sm border ${CATEGORY_TONE[r.category]} ${r.aisleAfter.includes(n + 1) ? 'mr-2' : ''}`} />
              ))}
            </div>
          </div>
        ))}
        <div className="mx-auto mt-3 h-1 w-3/4 rounded-full bg-gradient-to-r from-transparent via-sun-300 to-transparent" />
      </div>
    </div>
  );
}

function ScreenBuilder({ cinema, onClose, onSaved }: { cinema: CinemaView; onClose: () => void; onSaved: () => void }) {
  const [name, setName] = useState(`Audi ${cinema.screens.length + 1}`);
  const [rows, setRows] = useState<RowSpec[]>(PRESET);
  const [aisleText, setAisleText] = useState<string[]>(PRESET.map((r) => r.aisleAfter.join(', ')));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const seatCount = useMemo(() => rows.reduce((n, r) => n + r.seats, 0), [rows]);

  const update = (i: number, patch: Partial<RowSpec>) => setRows(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  const relabel = (list: RowSpec[]) => list.map((r, i) => ({ ...r, label: rowLabel(i) }));
  const addRow = () => {
    const last = rows[rows.length - 1] ?? { category: 'CLASSIC', seats: 12, aisleAfter: [6] };
    setRows(relabel([...rows, { ...last, label: '' }]));
    setAisleText([...aisleText, last.aisleAfter.join(', ')]);
  };
  const removeRow = (i: number) => {
    setRows(relabel(rows.filter((_, j) => j !== i)));
    setAisleText(aisleText.filter((_, j) => j !== i));
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await adminApi.createScreen(cinema.id, { name: name.trim(), rows });
      onSaved();
      onClose();
    } catch (err) {
      setError(err instanceof ApiError
        ? Object.values(err.fieldErrors).length ? Object.values(err.fieldErrors).join(' ') : err.message
        : 'Could not add the screen.');
      setBusy(false);
    }
  };

  return (
    <Modal title={`New screen at ${cinema.name}`} open onClose={onClose} wide
           footer={<>
             <span className="mr-auto self-center text-sm text-ink-400">{seatCount} seats</span>
             <button type="button" className="btn-ghost" onClick={onClose}>Cancel</button>
             <button type="submit" form="screen-form" className="btn-primary" disabled={busy || rows.length === 0}>{busy && <Spinner />} Add screen</button>
           </>}>
      <form id="screen-form" onSubmit={submit} className="space-y-4" noValidate>
        {error && <Alert>{error}</Alert>}
        <Field label="Screen name" value={name} onChange={(e) => setName(e.target.value)} />
        <div>
          <p className="label">Rows <span className="font-normal text-ink-400">(front, nearest the screen, first)</span></p>
          <div className="max-h-64 space-y-2 overflow-y-auto pr-1">
            {rows.map((r, i) => (
              <div key={i} className="grid grid-cols-[2rem_1fr_4.5rem_1fr_2rem] items-center gap-2">
                <span className="text-center text-sm font-bold text-white">{r.label}</span>
                <select aria-label={`Row ${r.label} category`} className="input py-1.5" value={r.category}
                        onChange={(e) => update(i, { category: e.target.value as SeatCategory })}>
                  {(['CLASSIC', 'PRIME', 'RECLINER'] as const).map((c) => <option key={c} value={c}>{CATEGORY_LABEL[c]}</option>)}
                </select>
                <input aria-label={`Row ${r.label} seats`} type="number" min={1} max={40} className="input py-1.5" value={r.seats}
                       onChange={(e) => update(i, { seats: Math.max(1, Math.min(40, Number(e.target.value) || 1)) })} />
                <input aria-label={`Row ${r.label} aisles after seats`} className="input py-1.5" placeholder="Aisles after, e.g. 4, 12"
                       value={aisleText[i] ?? ''}
                       onChange={(e) => {
                         setAisleText(aisleText.map((t, j) => (j === i ? e.target.value : t)));
                         update(i, { aisleAfter: e.target.value.split(',').map((x) => Number(x.trim())).filter((n) => n > 0 && Number.isInteger(n)) });
                       }} />
                <button type="button" className="btn-ghost px-0" aria-label={`Remove row ${r.label}`} onClick={() => removeRow(i)}>✕</button>
              </div>
            ))}
          </div>
          <button type="button" className="btn-secondary mt-3 px-3 py-1.5 text-xs" onClick={addRow} disabled={rows.length >= 26}>+ Add row</button>
        </div>
        <LayoutPreview rows={rows} />
      </form>
    </Modal>
  );
}

function CinemaForm({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const cities = useApi(adminApi.cities);
  const [form, setForm] = useState({ cityId: 0, name: '', address: '' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await adminApi.createCinema({ ...form, name: form.name.trim(), address: form.address.trim() });
      onSaved();
      onClose();
    } catch (err) {
      if (err instanceof ApiError) setFieldErrors(err.fieldErrors);
      setError(err instanceof ApiError ? err.message : 'Could not add the cinema.');
      setBusy(false);
    }
  };
  return (
    <Modal title="Add a cinema" open onClose={onClose}
           footer={<>
             <button type="button" className="btn-ghost" onClick={onClose}>Cancel</button>
             <button type="submit" form="cinema-form" className="btn-primary" disabled={busy || !form.cityId}>{busy && <Spinner />} Add cinema</button>
           </>}>
      <form id="cinema-form" onSubmit={submit} className="space-y-4" noValidate>
        {error && <Alert>{error}</Alert>}
        <div>
          <label htmlFor="city" className="label">City</label>
          <select id="city" className="input" value={form.cityId} onChange={(e) => setForm({ ...form, cityId: Number(e.target.value) })}>
            <option value={0} disabled>Choose a city</option>
            {cities.data?.map((c) => <option key={c.id} value={c.id}>{c.name}, {c.state}</option>)}
          </select>
        </div>
        <Field label="Name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} error={fieldErrors.name} placeholder="Starlight Cinemas: Park Street" />
        <Field label="Address" value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} error={fieldErrors.address} />
      </form>
    </Modal>
  );
}

export function AdminCinemasPage() {
  const cinemas = useApi(adminApi.cinemas);
  const [adding, setAdding] = useState(false);
  const [screenFor, setScreenFor] = useState<CinemaView | null>(null);
  const error = explainApiError(cinemas.error);
  const byCity = useMemo(() => {
    const groups = new Map<string, CinemaView[]>();
    cinemas.data?.forEach((c) => groups.set(c.cityName, [...(groups.get(c.cityName) ?? []), c]));
    return [...groups.entries()];
  }, [cinemas.data]);

  return (
    <div className="space-y-6">
      <PageHeader title="Cinemas" subtitle="Venues and their screens. A screen's seat layout is fixed once it's created."
                  action={<button type="button" className="btn-primary" onClick={() => setAdding(true)}>+ Add cinema</button>} />
      {error && <Alert>{error}</Alert>}
      {!cinemas.data ? (!error && <LoadingBlock />) : byCity.length === 0 ? (
        <div className="card"><EmptyState icon="🏛️" title="No cinemas yet" /></div>
      ) : byCity.map(([city, list]) => (
        <section key={city} className="space-y-3">
          <h2 className="text-sm font-bold uppercase tracking-widest text-ink-400">{city}</h2>
          <ul className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            {list.map((c) => (
              <li key={c.id} className="card p-5">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="font-bold text-white">{c.name}</p>
                    <p className="truncate text-xs text-ink-400">{c.address}</p>
                  </div>
                  <button type="button" className="btn-secondary shrink-0 px-3 py-1.5 text-xs" onClick={() => setScreenFor(c)}>+ Screen</button>
                </div>
                <ul className="mt-4 flex flex-wrap gap-2">
                  {c.screens.length === 0 && <li className="text-xs text-ink-500">No screens yet</li>}
                  {c.screens.map((s) => (
                    <li key={s.id} className="rounded-xl bg-white/[0.04] px-3 py-2 text-xs">
                      <p className="font-semibold text-white">{s.name}</p>
                      <p className="text-ink-400">{s.seatCount} seats</p>
                      <div className="mt-1 flex gap-1">{s.categories.map((cat) => <Badge key={cat} tone={cat === 'RECLINER' ? 'sun' : cat === 'PRIME' ? 'grape' : 'mint'}>{CATEGORY_LABEL[cat]}</Badge>)}</div>
                    </li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        </section>
      ))}
      {adding && <CinemaForm onClose={() => setAdding(false)} onSaved={cinemas.reload} />}
      {screenFor && <ScreenBuilder cinema={screenFor} onClose={() => setScreenFor(null)} onSaved={cinemas.reload} />}
    </div>
  );
}
