import { useEffect } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { RequireAuth } from './auth/RequireAuth';
import { AdminLayout } from './components/AdminLayout';
import { Layout } from './components/Layout';
import { AdminBookingsPage } from './pages/admin/BookingsPage';
import { AdminCinemasPage } from './pages/admin/CinemasPage';
import { DashboardPage } from './pages/admin/DashboardPage';
import { EmailsPage, SystemPage } from './pages/admin/InsightsPages';
import { AdminMoviesPage } from './pages/admin/MoviesPage';
import { AdminShowsPage } from './pages/admin/ShowsPage';
import { AccountPage, LoginPage, NotFoundPage, RegisterPage } from './pages/AuthPages';
import { BookingPage } from './pages/BookingPage';
import { CheckoutPage } from './pages/CheckoutPage';
import { HomePage } from './pages/HomePage';
import { MoviePage } from './pages/MoviePage';
import { MyBookingsPage } from './pages/MyBookingsPage';
import { PayPage } from './pages/PayPage';
import { SeatsPage } from './pages/SeatsPage';
import { WaitlistPage } from './pages/WaitlistPage';

function ScrollToTop() {
  const { pathname } = useLocation();
  useEffect(() => {
    window.scrollTo?.(0, 0);
  }, [pathname]);
  return null;
}

export function App() {
  return (
    <>
      <ScrollToTop />
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<HomePage />} />
          <Route path="movies/:movieId" element={<MoviePage />} />
          <Route path="shows/:showId" element={<SeatsPage />} />
          <Route path="login" element={<LoginPage />} />
          <Route path="register" element={<RegisterPage />} />

          <Route element={<RequireAuth />}>
            <Route path="account" element={<AccountPage />} />
            <Route path="bookings/:reference" element={<BookingPage />} />
          </Route>
          <Route element={<RequireAuth role="ROLE_CUSTOMER" />}>
            <Route path="checkout/:holdId" element={<CheckoutPage />} />
            <Route path="bookings" element={<MyBookingsPage />} />
            <Route path="waitlist" element={<WaitlistPage />} />
          </Route>
          <Route path="*" element={<NotFoundPage />} />
        </Route>

        {/* The mock gateway's page stands alone, like a bank's would */}
        <Route element={<RequireAuth role="ROLE_CUSTOMER" />}>
          <Route path="pay/:gatewayRef" element={<PayPage />} />
        </Route>

        <Route path="admin" element={<RequireAuth role="ROLE_ADMIN" />}>
          <Route element={<AdminLayout />}>
            <Route index element={<DashboardPage />} />
            <Route path="bookings" element={<AdminBookingsPage />} />
            <Route path="movies" element={<AdminMoviesPage />} />
            <Route path="cinemas" element={<AdminCinemasPage />} />
            <Route path="shows" element={<AdminShowsPage />} />
            <Route path="emails" element={<EmailsPage />} />
            <Route path="system" element={<SystemPage />} />
            <Route path="*" element={<Navigate to="/admin" replace />} />
          </Route>
        </Route>
      </Routes>
    </>
  );
}
