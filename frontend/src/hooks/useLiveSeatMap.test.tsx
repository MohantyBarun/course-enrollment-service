import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { seatMap } from '../test/fixtures';
import { jsonResponse, mockFetch } from '../test/helpers';
import { indexSeats } from '../lib/seatMap';
import { useLiveSeatMap } from './useLiveSeatMap';

/** Minimal EventSource stand-in that the test drives by hand. */
class FakeEventSource {
  static CLOSED = 2;
  static last: FakeEventSource;
  readyState = 1;
  onerror: (() => void) | null = null;
  closed = false;
  private listeners = new Map<string, ((e: MessageEvent<string>) => void)[]>();
  constructor(public url: string) {
    FakeEventSource.last = this;
  }
  addEventListener(name: string, fn: (e: MessageEvent<string>) => void) {
    this.listeners.set(name, [...(this.listeners.get(name) ?? []), fn]);
  }
  emit(name: string, data: unknown = {}) {
    this.listeners.get(name)?.forEach((fn) => fn({ data: JSON.stringify(data) } as MessageEvent<string>));
  }
  close() {
    this.closed = true;
  }
}

afterEach(() => vi.unstubAllGlobals());

const statusOf = (map: ReturnType<typeof seatMap> | null, id: number) => indexSeats(map!).get(id)!.seat.status;

describe('useLiveSeatMap', () => {
  it('fetches only once the stream is live, and replays events that arrive during the fetch', async () => {
    vi.stubGlobal('EventSource', FakeEventSource);
    let release!: () => void;
    const fetchMock = mockFetch(() => new Promise<Response>((resolve) => {
      release = () => resolve(jsonResponse(seatMap()));
    }));

    const { result, unmount } = renderHook(() => useLiveSeatMap(1));
    expect(FakeEventSource.last.url).toMatch(/\/shows\/1\/seats\/stream$/);
    expect(fetchMock).not.toHaveBeenCalled();

    act(() => FakeEventSource.last.emit('ready', { showId: 1 }));
    expect(result.current.live).toBe(true);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));

    // Seat 1 is taken while the snapshot is on its way: it must not be lost
    act(() => FakeEventSource.last.emit('seats', { showId: 1, changes: [{ seatId: 1, status: 'HELD' }] }));
    await act(async () => release());
    await waitFor(() => expect(result.current.map).not.toBeNull());
    expect(statusOf(result.current.map, 1)).toBe('HELD');
    expect(result.current.map!.available).toBe(2);

    // Later events apply straight away
    act(() => FakeEventSource.last.emit('seats', { showId: 1, changes: [{ seatId: 1, status: 'BOOKED' }, { seatId: 2, status: 'HELD' }] }));
    expect(statusOf(result.current.map, 1)).toBe('BOOKED');
    expect(statusOf(result.current.map, 2)).toBe('HELD');

    unmount();
    expect(FakeEventSource.last.closed).toBe(true);
  });

  it('refetches on "reload"', async () => {
    vi.stubGlobal('EventSource', FakeEventSource);
    let calls = 0;
    mockFetch(() => {
      calls += 1;
      return jsonResponse(calls === 1 ? seatMap() : seatMap({ bookable: false }));
    });
    const { result } = renderHook(() => useLiveSeatMap(1));

    act(() => FakeEventSource.last.emit('ready'));
    await waitFor(() => expect(result.current.map?.bookable).toBe(true));
    act(() => FakeEventSource.last.emit('reload', { showId: 1 }));
    await waitFor(() => expect(result.current.map?.bookable).toBe(false));
  });

  it('falls back to fetching when EventSource is unavailable', async () => {
    vi.stubGlobal('EventSource', undefined);
    mockFetch(() => jsonResponse(seatMap()));
    const { result } = renderHook(() => useLiveSeatMap(1));
    await waitFor(() => expect(result.current.map).not.toBeNull());
    expect(result.current.live).toBe(false);
  });
});
