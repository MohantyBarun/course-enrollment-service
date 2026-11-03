import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { catalogApi } from '../api/endpoints';
import type { City } from '../api/types';

interface CityContextValue {
  cities: City[];
  city: City | null;
  setCity: (city: City) => void;
  /** True until the first city list arrives. */
  loading: boolean;
  error: string | null;
  /** Ask the layout to open the city picker. */
  pickerOpen: boolean;
  setPickerOpen: (open: boolean) => void;
}

const CityContext = createContext<CityContextValue | null>(null);
const CITY_KEY = 'seatsure.cityId';

function readStoredCityId(): number | null {
  try {
    const raw = localStorage.getItem(CITY_KEY);
    return raw ? Number(raw) : null;
  } catch {
    return null;
  }
}

export function CityProvider({ children }: { children: ReactNode }) {
  const [cities, setCities] = useState<City[]>([]);
  const [cityId, setCityId] = useState<number | null>(readStoredCityId);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [pickerOpen, setPickerOpen] = useState(false);

  useEffect(() => {
    let live = true;
    catalogApi.cities()
      .then((list) => {
        if (!live) return;
        setCities(list);
        // First visit (or the stored city has no cinemas any more): ask where they are
        if (!list.some((c) => c.id === readStoredCityId())) setPickerOpen(true);
      })
      .catch((e: Error) => live && setError(e.message))
      .finally(() => live && setLoading(false));
    return () => {
      live = false;
    };
  }, []);

  const setCity = useCallback((c: City) => {
    setCityId(c.id);
    try {
      localStorage.setItem(CITY_KEY, String(c.id));
    } catch {
      // Private mode: the choice just isn't remembered
    }
  }, []);

  const city = cities.find((c) => c.id === cityId) ?? null;
  const value = useMemo(() => ({ cities, city, setCity, loading, error, pickerOpen, setPickerOpen }),
    [cities, city, setCity, loading, error, pickerOpen]);
  return <CityContext.Provider value={value}>{children}</CityContext.Provider>;
}

export function useCity(): CityContextValue {
  const ctx = useContext(CityContext);
  if (!ctx) throw new Error('useCity must be used inside <CityProvider>');
  return ctx;
}
