import { describe, expect, it } from 'vitest';
import { istLocalToInstant } from '../pages/admin/ShowsPage';
import { seatMap } from '../test/fixtures';
import {
  addDays, dateParts, dayLabel, formatCountdown, formatRupees, formatRuntime, formatTime, istDate, relativeTime,
} from './format';
import { isPasswordValid } from './passwordRules';
import { posterGradient } from './poster';
import { applySeatChanges, categoryBlocks, indexSeats, takenFromSelection } from './seatMap';

describe('applySeatChanges', () => {
  it('updates statuses and the available count', () => {
    const next = applySeatChanges(seatMap(), { showId: 1, changes: [{ seatId: 1, status: 'HELD' }, { seatId: 5, status: 'AVAILABLE' }] });
    expect(indexSeats(next).get(1)!.seat.status).toBe('HELD');
    expect(indexSeats(next).get(5)!.seat.status).toBe('AVAILABLE');
    expect(next.available).toBe(3);
  });

  it('counts held to booked as no change in availability', () => {
    const next = applySeatChanges(seatMap(), { showId: 1, changes: [{ seatId: 5, status: 'BOOKED' }] });
    expect(next.available).toBe(3);
  });

  it('returns the same object when nothing changes (replayed or other show)', () => {
    const map = seatMap();
    expect(applySeatChanges(map, { showId: 1, changes: [{ seatId: 3, status: 'BOOKED' }] })).toBe(map);
    expect(applySeatChanges(map, { showId: 2, changes: [{ seatId: 1, status: 'BOOKED' }] })).toBe(map);
  });

  it('keeps unchanged rows by reference', () => {
    const map = seatMap();
    const next = applySeatChanges(map, { showId: 1, changes: [{ seatId: 4, status: 'HELD' }] });
    expect(next.rows[0]).toBe(map.rows[0]);
    expect(next.rows[1]).not.toBe(map.rows[1]);
  });
});

describe('seat map helpers', () => {
  it('labels seats by row and number', () => {
    expect(indexSeats(seatMap()).get(4)).toMatchObject({ label: 'B1', category: 'RECLINER' });
  });

  it('finds selected seats that are no longer available', () => {
    expect(takenFromSelection(seatMap(), new Set([1, 3, 5]))).toEqual([3, 5]);
  });

  it('groups rows into category blocks from the back of the hall', () => {
    expect(categoryBlocks(seatMap()).map((b) => b.category)).toEqual(['RECLINER', 'CLASSIC']);
  });
});

describe('format', () => {
  it('formats rupees from paise', () => {
    expect(formatRupees(25000)).toBe('₹250');
    expect(formatRupees(25050)).toBe('₹250.50');
  });

  it('shows times and dates in IST whatever the viewer is in', () => {
    expect(formatTime('2026-11-01T15:15:00Z')).toBe('8:45 PM');
    expect(istDate('2026-11-01T20:00:00Z')).toBe('2026-11-02');
  });

  it('turns an IST wall-clock time into an instant', () => {
    expect(istLocalToInstant('2026-11-02T18:30')).toBe('2026-11-02T13:00:00.000Z');
  });

  it('builds the date strip labels', () => {
    expect(dateParts('2026-11-01')).toEqual({ weekday: 'SUN', day: '1', month: 'NOV' });
    expect(dayLabel('2026-11-01', '2026-11-01')).toBe('TODAY');
    expect(dayLabel('2026-11-02', '2026-11-01')).toBe('TMRW');
    expect(dayLabel('2026-11-03', '2026-11-01')).toBe('TUE');
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01');
  });

  it('formats running times, countdowns and relative times', () => {
    expect(formatRuntime(152)).toBe('2h 32m');
    expect(formatRuntime(120)).toBe('2h');
    expect(formatCountdown(545)).toBe('09:05');
    expect(formatCountdown(-3)).toBe('00:00');
    const now = Date.parse('2026-11-01T12:00:00Z');
    expect(relativeTime('2026-11-01T11:55:00Z', now)).toBe('5m ago');
    expect(relativeTime('2026-11-01T12:00:20Z', now)).toBe('in 20s');
  });
});

describe('misc', () => {
  it('matches the backend password policy', () => {
    expect(isPasswordValid('Secret#123')).toBe(true);
    expect(isPasswordValid('secret#123')).toBe(false);
    expect(isPasswordValid('Secret1234')).toBe(false);
  });

  it('gives each movie a stable generated poster', () => {
    expect(posterGradient('Paper Boats')).toBe(posterGradient('Paper Boats'));
    expect(posterGradient('Paper Boats')).toMatch(/^linear-gradient/);
  });
});
