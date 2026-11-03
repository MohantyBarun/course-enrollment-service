import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Navigate, Route } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
import type { City, Home } from '../api/types';
import { RequireAuth } from '../auth/RequireAuth';
import { Layout } from '../components/Layout';
import { hold, movie, seatMap, showDetail } from '../test/fixtures';
import { jsonResponse, mockFetch, renderAt, signIn, signInAsAdmin } from '../test/helpers';
import { LoginPage } from './AuthPages';
import { BookingPage } from './BookingPage';
import { CheckoutPage } from './CheckoutPage';
import { HomePage } from './HomePage';
import { SeatsPage } from './SeatsPage';

const cities: City[] = [{ id: 1, name: 'Bhubaneswar', state: 'Odisha' }, { id: 2, name: 'Mumbai', state: 'Maharashtra' }];
const home: Home = {
  city: cities[0],
  nowShowing: [movie, { ...movie, id: 11, title: 'Monsoon Protocol', language: 'Hindi', genres: ['Action'] }],
  comingSoon: [{ ...movie, id: 12, title: 'Code Red: Bengaluru', releaseDate: '2030-01-10' }],
};

beforeEach(() => {
  Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
});

function body(init: RequestInit) {
  return JSON.parse(String(init.body));
}

describe('HomePage', () => {
  it("shows the stored city's movies and filters by language", async () => {
    localStorage.setItem('seatsure.cityId', '1');
    mockFetch((url) => {
      if (url.pathname === '/cities') return jsonResponse(cities);
      if (url.pathname === '/cities/1/home') return jsonResponse(home);
      return jsonResponse({}, 404);
    });
    renderAt('/', <Route path="/" element={<HomePage />} />);

    expect(await screen.findByRole('heading', { name: /now showing in bhubaneswar/i })).toBeInTheDocument();
    expect(screen.getAllByText('Monsoon Protocol').length).toBeGreaterThan(0);
    expect(screen.getByText('Coming soon ✨')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Tamil' }));
    const grid = screen.getByRole('heading', { name: /now showing/i }).closest('section')!;
    expect(within(grid).queryByText('Monsoon Protocol')).not.toBeInTheDocument();
    expect(within(grid).getAllByText('Paper Boats').length).toBeGreaterThan(0);
  });

  it('asks for a city on the first visit', async () => {
    mockFetch((url) => (url.pathname === '/cities' ? jsonResponse(cities) : jsonResponse(home)));
    renderAt('/', <Route element={<Layout />}><Route path="/" element={<HomePage />} /></Route>);

    expect(await screen.findByRole('dialog', { name: /where are you watching/i })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /mumbai/i }));
    expect(localStorage.getItem('seatsure.cityId')).toBe('2');
  });
});

function seatRoutes() {
  return (
    <>
      <Route path="/shows/:showId" element={<SeatsPage />} />
      <Route path="/checkout/:holdId" element={<p>CHECKOUT PAGE</p>} />
      <Route path="/waitlist" element={<p>WAITLIST PAGE</p>} />
    </>
  );
}

function seatsBackend(extra: (url: URL, init: RequestInit) => Response | null = () => null) {
  return mockFetch((url, init) => {
    const custom = extra(url, init);
    if (custom) return custom;
    if (url.pathname === '/cities') return jsonResponse(cities);
    if (url.pathname === '/shows/1') return jsonResponse(showDetail);
    if (url.pathname === '/shows/1/seats') return jsonResponse(seatMap());
    return jsonResponse({ error: 'unexpected' }, 500);
  });
}

describe('SeatsPage', () => {
  it('holds the selected seats and goes to checkout', async () => {
    signIn();
    const fetchMock = seatsBackend((url, init) =>
      url.pathname === '/shows/1/holds' && init.method === 'POST' ? jsonResponse(hold(), 201) : null);
    renderAt('/shows/1', seatRoutes());

    await userEvent.click(await screen.findByRole('button', { name: /seat a1,/i }));
    await userEvent.click(screen.getByRole('button', { name: /seat b1,/i }));
    expect(screen.getByText('A1, B1')).toBeInTheDocument();
    expect(screen.getByText('₹700')).toBeInTheDocument();
    // Taken seats can't be picked
    expect(screen.getByRole('button', { name: /seat a3,/i })).toBeDisabled();

    await userEvent.click(screen.getByRole('button', { name: /hold seats/i }));

    expect(await screen.findByText('CHECKOUT PAGE')).toBeInTheDocument();
    const holdCall = fetchMock.mock.calls.find(([u]) => String(u).endsWith('/shows/1/holds'))!;
    expect(body(holdCall[1]!)).toEqual({ seatIds: [1, 4] });
    expect((holdCall[1]!.headers as Record<string, string>)['Idempotency-Key']).toBeTruthy();
  });

  it('drops seats someone else took first and keeps the rest', async () => {
    signIn();
    seatsBackend((url, init) => (url.pathname === '/shows/1/holds' && init.method === 'POST'
      ? jsonResponse({ status: 409, error: 'Some of these seats were just taken', unavailableSeatIds: [4] }, 409) : null));
    renderAt('/shows/1', seatRoutes());

    await userEvent.click(await screen.findByRole('button', { name: /seat a1,/i }));
    await userEvent.click(screen.getByRole('button', { name: /seat b1,/i }));
    await userEvent.click(screen.getByRole('button', { name: /hold seats/i }));

    expect(await screen.findByText('Some of these seats were just taken')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /seat a1,.*selected/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /seat b1,.*selected/i })).not.toBeInTheDocument();
  });

  it('sends a signed-out visitor to sign in first', async () => {
    seatsBackend();
    renderAt('/shows/1', seatRoutes());

    await userEvent.click(await screen.findByRole('button', { name: /seat a1,/i }));
    await userEvent.click(screen.getByRole('button', { name: /sign in to book/i }));

    expect(await screen.findByText('LOGIN PAGE')).toBeInTheDocument();
  });

  it('offers the waitlist when the show is sold out', async () => {
    signIn();
    const soldOut = seatMap({
      available: 0,
      rows: seatMap().rows.map((r) => ({ ...r, seats: r.seats.map((s) => ({ ...s, status: 'BOOKED' as const })) })),
    });
    const fetchMock = seatsBackend((url, init) => {
      if (url.pathname === '/shows/1/seats') return jsonResponse(soldOut);
      if (url.pathname === '/shows/1/waitlist' && init.method === 'POST') return jsonResponse({ id: 1 }, 201);
      return null;
    });
    renderAt('/shows/1', seatRoutes());

    expect(await screen.findByText(/sold out\. join the waitlist/i)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '3' }));
    await userEvent.click(screen.getByRole('button', { name: /join waitlist for 3 seats/i }));

    expect(await screen.findByText('WAITLIST PAGE')).toBeInTheDocument();
    const call = fetchMock.mock.calls.find(([u]) => String(u).endsWith('/shows/1/waitlist'))!;
    expect(body(call[1]!)).toEqual({ seats: 3 });
  });
});

describe('CheckoutPage', () => {
  function checkoutRoutes() {
    return (
      <>
        <Route path="/checkout/:holdId" element={<CheckoutPage />} />
        <Route path="/pay/:ref" element={<p>PAY PAGE</p>} />
        <Route path="/bookings/:reference" element={<p>BOOKING PAGE</p>} />
      </>
    );
  }

  it('starts the payment and opens the payment page', async () => {
    signIn();
    mockFetch((url, init) => {
      if (url.pathname === '/cities') return jsonResponse(cities);
      if (url.pathname === '/holds/9') return jsonResponse(hold());
      if (url.pathname === '/holds/9/payments' && init.method === 'POST') {
        return jsonResponse({ gatewayRef: 'pay_abc', amountPaise: 23000, status: 'PENDING', checkoutPath: '/pay/pay_abc' }, 201);
      }
      return jsonResponse({}, 500);
    });
    renderAt('/checkout/9', checkoutRoutes());

    expect(await screen.findByRole('timer')).toBeInTheDocument();
    expect(screen.getAllByText('₹230').length).toBeGreaterThan(0);
    await userEvent.click(screen.getByRole('button', { name: /pay ₹230/i }));
    expect(await screen.findByText('PAY PAGE')).toBeInTheDocument();
  });

  it('waits for the webhook, then shows the ticket', async () => {
    signIn();
    let polls = 0;
    mockFetch((url) => {
      if (url.pathname === '/cities') return jsonResponse(cities);
      polls += 1;
      const pending = { gatewayRef: 'pay_abc', status: 'PENDING' as const, failureReason: null, updatedAt: '2030-01-01T00:00:00Z' };
      return jsonResponse(polls < 2 ? hold({ latestPayment: pending })
        : hold({ status: 'CONVERTED', latestPayment: { ...pending, status: 'SUCCEEDED' }, bookingReference: 'SS-7K2P9Q' }));
    });
    renderAt('/checkout/9', checkoutRoutes());

    expect(await screen.findByText(/confirming your payment/i)).toBeInTheDocument();
    expect(await screen.findByText('BOOKING PAGE', {}, { timeout: 4000 })).toBeInTheDocument();
  });

  it('explains an expired hold', async () => {
    signIn();
    mockFetch((url) => (url.pathname === '/cities' ? jsonResponse(cities) : jsonResponse(hold({ status: 'EXPIRED' }))));
    renderAt('/checkout/9', checkoutRoutes());

    expect(await screen.findByText(/10 minutes ran out/i)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /pick seats again/i })).toHaveAttribute('href', '/shows/1');
  });
});

describe('BookingPage', () => {
  it('shows the ticket and cancels it', async () => {
    signIn();
    const booking = {
      reference: 'SS-7K2P9Q', status: 'CONFIRMED', showId: 1, movieId: 10, movieTitle: 'Paper Boats', posterUrl: null,
      cinemaName: 'Starlight Cinemas: Patia', cityName: 'Bhubaneswar', screenName: 'Audi 1', startsAt: '2030-01-01T15:15:00Z',
      format: '2D', seats: [{ label: 'A1', category: 'CLASSIC', pricePaise: 20000 }], ticketsPaise: 20000,
      convenienceFeePaise: 3000, totalPaise: 23000, bookedAt: '2029-12-30T10:00:00Z', cancelledAt: null, cancelReason: null,
      cancellable: true,
    };
    mockFetch((url, init) => {
      if (url.pathname === '/cities') return jsonResponse(cities);
      if (url.pathname.endsWith('/cancel') && init.method === 'POST') {
        return jsonResponse({ ...booking, status: 'CANCELLED', cancellable: false, cancelledAt: new Date().toISOString() });
      }
      return jsonResponse(booking);
    });
    renderAt('/bookings/SS-7K2P9Q', <Route path="/bookings/:reference" element={<BookingPage />} />);

    expect(await screen.findByText('SS-7K2P9Q')).toBeInTheDocument();
    expect(screen.getByText('Confirmed')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Cancel booking' }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel booking' }));

    expect(await screen.findByText('Cancelled')).toBeInTheDocument();
    expect(screen.getByText('Refunded')).toBeInTheDocument();
  });
});

describe('sign-in and guards', () => {
  it('sends a signed-out visitor to sign in', () => {
    mockFetch(() => jsonResponse(cities));
    renderAt('/bookings', (
      <Route element={<RequireAuth role="ROLE_CUSTOMER" />}>
        <Route path="/bookings" element={<p>BOOKINGS</p>} />
      </Route>
    ));
    expect(screen.getByText('LOGIN PAGE')).toBeInTheDocument();
  });

  it('keeps admins out of checkout', () => {
    signInAsAdmin();
    mockFetch(() => jsonResponse(cities));
    renderAt('/checkout/9', (
      <>
        <Route element={<RequireAuth role="ROLE_CUSTOMER" />}>
          <Route path="/checkout/:holdId" element={<p>CHECKOUT</p>} />
        </Route>
        <Route path="/admin" element={<p>ADMIN HOME</p>} />
      </>
    ));
    expect(screen.getByText('ADMIN HOME')).toBeInTheDocument();
  });

  it('signs in as the demo customer and returns to the page they came from', async () => {
    const tokenResponse = {
      accessToken: 'header.eyJzdWIiOiJkZW1vQHNlYXRzdXJlLmRldiIsInJvbGUiOiJST0xFX0NVU1RPTUVSIiwiZXhwIjo0MTAyNDQ0ODAwfQ.sig',
      refreshToken: 'r1', tokenType: 'Bearer', expiresIn: 900,
      user: { id: 1, name: 'Demo Customer', email: 'demo@seatsure.dev', phone: null, role: 'ROLE_CUSTOMER' },
    };
    mockFetch((url) => {
      if (url.pathname === '/cities') return jsonResponse(cities);
      if (url.pathname === '/auth/demo') return jsonResponse({ enabled: true });
      if (url.pathname === '/auth/demo-login') return jsonResponse(tokenResponse);
      return jsonResponse({}, 500);
    });
    // Arrive at /login the way RequireAuth sends people: with where they came from
    // (The harness maps /login to a marker, so the real page is mounted at /signin here)
    renderAt('/start', (
      <>
        <Route path="/start" element={<RedirectToLogin />} />
        <Route path="/signin" element={<LoginPage />} />
        <Route path="/shows/:id" element={<p>SEAT MAP</p>} />
      </>
    ));
    fireEvent.click(await screen.findByRole('button', { name: /demo customer/i }));

    await waitFor(() => expect(screen.getByText('SEAT MAP')).toBeInTheDocument());
    expect(localStorage.getItem('seatsure.refreshToken')).toBe('r1');
  });
});

function RedirectToLogin() {
  return <Navigate to="/signin" replace state={{ from: '/shows/1' }} />;
}
