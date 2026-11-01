import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/client';

interface State<T> {
  data: T | null;
  error: string | null;
  loading: boolean;
}

/**
 * Loads data on mount and whenever `deps` change; ignores responses from superseded requests.
 * `reload()` refetches without clearing the current data (no flicker).
 */
export function useApi<T>(load: () => Promise<T>, deps: unknown[] = []) {
  const [state, setState] = useState<State<T>>({ data: null, error: null, loading: true });
  const seq = useRef(0);

  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(load, deps);

  const reload = useCallback(async () => {
    const id = ++seq.current;
    setState((s) => ({ ...s, loading: true, error: null }));
    try {
      const data = await run();
      if (id === seq.current) setState({ data, error: null, loading: false });
    } catch (err) {
      if (id === seq.current) {
        setState((s) => ({ ...s, loading: false, error: err instanceof ApiError ? err.message : 'Something went wrong.' }));
      }
    }
  }, [run]);

  useEffect(() => {
    reload();
  }, [reload]);

  return { ...state, reload, setData: (data: T) => setState((s) => ({ ...s, data })) };
}
