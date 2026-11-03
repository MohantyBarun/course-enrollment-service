import type { HoldView, MovieCard, SeatMap, ShowDetail } from '../api/types';

export function seatMap(overrides: Partial<SeatMap> = {}): SeatMap {
  return {
    showId: 1, bookable: true, maxCol: 5, available: 3, total: 5,
    prices: [{ category: 'CLASSIC', pricePaise: 20000 }, { category: 'RECLINER', pricePaise: 50000 }],
    rows: [
      { label: 'A', category: 'CLASSIC', seats: [
        { id: 1, number: 1, col: 1, status: 'AVAILABLE' },
        { id: 2, number: 2, col: 2, status: 'AVAILABLE' },
        { id: 3, number: 3, col: 4, status: 'BOOKED' },
      ] },
      { label: 'B', category: 'RECLINER', seats: [
        { id: 4, number: 1, col: 1, status: 'AVAILABLE' },
        { id: 5, number: 2, col: 2, status: 'HELD' },
      ] },
    ],
    ...overrides,
  };
}

export const movie: MovieCard = {
  id: 10, title: 'Paper Boats', language: 'Tamil', certificate: 'U', genres: ['Family', 'Drama'],
  durationMinutes: 118, releaseDate: '2026-10-01', posterUrl: null,
};

export const showDetail: ShowDetail = {
  showId: 1, movie, cinema: { id: 3, name: 'Starlight Cinemas: Patia', address: 'KIIT Road', city: 'Bhubaneswar' },
  screenName: 'Audi 1', startsAt: '2030-01-01T15:15:00Z', endsAt: '2030-01-01T17:30:00Z', format: '2D', status: 'SCHEDULED',
  bookable: true, prices: [{ category: 'CLASSIC', pricePaise: 20000 }, { category: 'RECLINER', pricePaise: 50000 }],
};

export function hold(overrides: Partial<HoldView> = {}): HoldView {
  return {
    holdId: 9, status: 'ACTIVE', source: 'CUSTOMER', showId: 1, movieTitle: 'Paper Boats', cinemaName: 'Starlight Cinemas: Patia',
    screenName: 'Audi 1', startsAt: '2030-01-01T15:15:00Z', format: '2D',
    seats: [{ seatId: 1, label: 'A1', category: 'CLASSIC', pricePaise: 20000 }],
    ticketsPaise: 20000, convenienceFeePaise: 3000, totalPaise: 23000,
    expiresAt: new Date(Date.now() + 9 * 60_000).toISOString(), latestPayment: null, bookingReference: null,
    ...overrides,
  };
}
