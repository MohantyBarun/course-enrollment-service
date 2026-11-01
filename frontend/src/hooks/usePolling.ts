import { useEffect, useRef } from 'react';

/** Calls `callback` every `intervalMs` while the tab is visible (no background polling). */
export function usePolling(callback: () => void, intervalMs: number) {
  const latest = useRef(callback);
  useEffect(() => {
    latest.current = callback;
  });
  useEffect(() => {
    const id = setInterval(() => {
      if (document.visibilityState === 'visible') latest.current();
    }, intervalMs);
    return () => clearInterval(id);
  }, [intervalMs]);
}
