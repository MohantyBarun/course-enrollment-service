import { useCity } from '../hooks/useCity';
import { posterGradient } from '../lib/poster';
import { Alert, Modal, Spinner } from './ui';

export function CityPicker() {
  const { cities, city, setCity, loading, error, pickerOpen, setPickerOpen } = useCity();

  return (
    <Modal title="Where are you watching?" open={pickerOpen} onClose={() => setPickerOpen(false)} wide>
      {error && <Alert>{error}</Alert>}
      {loading && <p className="flex items-center gap-2 text-sm text-ink-400"><Spinner /> Loading cities…</p>}
      <ul className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        {cities.map((c) => {
          const selected = c.id === city?.id;
          return (
            <li key={c.id}>
              <button type="button" aria-pressed={selected}
                      onClick={() => {
                        setCity(c);
                        setPickerOpen(false);
                      }}
                      className={`group relative flex h-24 w-full flex-col justify-end overflow-hidden rounded-2xl p-3 text-left transition hover:-translate-y-0.5
                        ${selected ? 'ring-2 ring-sun-400 ring-offset-2 ring-offset-ink-900' : ''}`}
                      style={{ backgroundImage: posterGradient(c.name) }}>
                <span className="absolute inset-0 bg-gradient-to-t from-black/60 to-transparent" aria-hidden="true" />
                <span className="relative text-sm font-extrabold text-white">{c.name}</span>
                <span className="relative text-xs text-white/75">{c.state}</span>
              </button>
            </li>
          );
        })}
      </ul>
    </Modal>
  );
}
