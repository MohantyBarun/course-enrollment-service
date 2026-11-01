import { useCallback, useEffect, useRef, useState } from 'react';
import { API_BASE_URL, ApiError } from '../api/client';
import { catalogApi } from '../api/endpoints';
import type { SeatMap, SeatsEvent } from '../api/types';
import { applySeatChanges } from '../lib/seatMap';

const FALLBACK_POLL_MS = 15_000;
const READY_TIMEOUT_MS = 5_000;

/**
 * The seat map of a show, kept current by Server-Sent Events.
 *
 * Subscribe first, then fetch: the stream says "ready" once it's live, and only then is the map
 * fetched, so no change can slip between the snapshot and the stream. Events that arrive while the
 * fetch is in flight are buffered and replayed on top of it (each carries a seat's absolute status,
 * so replaying one the snapshot already includes is harmless). Browsers reconnect a dropped stream
 * by themselves, and each reconnect says "ready" again, which refetches the map.
 */
export function useLiveSeatMap(showId: number) {
  const [map, setMap] = useState<SeatMap | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [live, setLive] = useState(false);
  const loadRef = useRef<() => Promise<void>>(async () => undefined);

  useEffect(() => {
    let cancelled = false;
    let seq = 0;
    let buffered: SeatsEvent[] | null = null; // non-null while a fetch is in flight
    let loaded = false;
    let pollTimer: ReturnType<typeof setInterval> | undefined;

    const load = async () => {
      const id = ++seq;
      buffered = [];
      try {
        let fresh = await catalogApi.seats(showId);
        if (cancelled || id !== seq) return;
        for (const event of buffered) fresh = applySeatChanges(fresh, event);
        buffered = null;
        loaded = true;
        setMap(fresh);
        setError(null);
      } catch (e) {
        if (cancelled || id !== seq) return;
        buffered = null;
        setError(e instanceof ApiError ? e.message : 'Could not load the seat map.');
      }
    };
    loadRef.current = load;

    const startPolling = () => {
      if (pollTimer) return;
      void load();
      pollTimer = setInterval(() => document.visibilityState === 'visible' && load(), FALLBACK_POLL_MS);
    };

    if (typeof EventSource === 'undefined') {
      startPolling();
      return () => {
        cancelled = true;
        clearInterval(pollTimer);
      };
    }

    const source = new EventSource(`${API_BASE_URL}/shows/${showId}/seats/stream`);
    source.addEventListener('ready', () => {
      setLive(true);
      void load();
    });
    source.addEventListener('seats', (e) => {
      const event = JSON.parse((e as MessageEvent<string>).data) as SeatsEvent;
      if (buffered) buffered.push(event);
      else setMap((m) => (m ? applySeatChanges(m, event) : m));
    });
    source.addEventListener('reload', () => void load());
    source.onerror = () => {
      setLive(false);
      // CLOSED means the browser gave up (e.g. the endpoint answered with an error): poll instead
      if (source.readyState === EventSource.CLOSED) startPolling();
    };
    // A proxy that buffers the stream would delay "ready": show the map anyway
    const readyTimer = setTimeout(() => !loaded && load(), READY_TIMEOUT_MS);

    return () => {
      cancelled = true;
      clearTimeout(readyTimer);
      clearInterval(pollTimer);
      source.close();
    };
  }, [showId]);

  const reload = useCallback(() => loadRef.current(), []);
  return { map, error, live, reload };
}
