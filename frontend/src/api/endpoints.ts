import { apiRequest, newIdempotencyKey } from './client';
import type {
  AdminBookingView, AdminMovie, AdminShowView, BookingView, CinemaView, City, Dashboard, Home, HoldView, MovieDetail, MovieRequest,
  NotificationFeed, Paged, PaymentStarted, RowSpec, ScreenView, SeatMap, ShowDetail, ShowRequest, Showtimes,
  SystemSnapshot, TokenResponse, UserView, WaitlistView,
} from './types';

export const authApi = {
  login: (email: string, password: string) =>
    apiRequest<TokenResponse>('/auth/login', { method: 'POST', auth: false, body: { email, password } }),
  register: (body: { name: string; email: string; phone: string; password: string }) =>
    apiRequest<TokenResponse>('/auth/register', { method: 'POST', auth: false, body }),
  logout: (refreshToken: string) =>
    apiRequest<void>('/auth/logout', { method: 'POST', auth: false, body: { refreshToken } }),
  me: () => apiRequest<UserView>('/auth/me'),
  changePassword: (currentPassword: string, newPassword: string) =>
    apiRequest<void>('/auth/change-password', { method: 'POST', body: { currentPassword, newPassword } }),
  demoStatus: () => apiRequest<{ enabled: boolean }>('/auth/demo', { auth: false }),
  demoLogin: (role: 'ADMIN' | 'CUSTOMER') =>
    apiRequest<TokenResponse>('/auth/demo-login', { method: 'POST', auth: false, body: { role } }),
};

/** Public browsing: no token needed. */
export const catalogApi = {
  cities: () => apiRequest<City[]>('/cities', { auth: false }),
  home: (cityId: number) => apiRequest<Home>(`/cities/${cityId}/home`, { auth: false }),
  movie: (movieId: number) => apiRequest<MovieDetail>(`/movies/${movieId}`, { auth: false }),
  showtimes: (movieId: number, cityId: number, date?: string) =>
    apiRequest<Showtimes>(`/movies/${movieId}/showtimes`, { auth: false, query: { cityId, date } }),
  show: (showId: number) => apiRequest<ShowDetail>(`/shows/${showId}`, { auth: false }),
  seats: (showId: number) => apiRequest<SeatMap>(`/shows/${showId}/seats`, { auth: false }),
};

export const checkoutApi = {
  hold: (showId: number, seatIds: number[]) =>
    apiRequest<HoldView>(`/shows/${showId}/holds`, { method: 'POST', body: { seatIds }, idempotencyKey: newIdempotencyKey() }),
  get: (holdId: number) => apiRequest<HoldView>(`/holds/${holdId}`),
  release: (holdId: number) => apiRequest<void>(`/holds/${holdId}`, { method: 'DELETE' }),
  pay: (holdId: number) =>
    apiRequest<PaymentStarted>(`/holds/${holdId}/payments`, { method: 'POST', idempotencyKey: newIdempotencyKey() }),
  completeMockPayment: (gatewayRef: string, outcome: 'SUCCESS' | 'FAILURE') =>
    apiRequest<{ status: string }>(`/mock-gateway/payments/${gatewayRef}`, { method: 'POST', body: { outcome } }),
  joinWaitlist: (showId: number, seats: number) =>
    apiRequest<WaitlistView>(`/shows/${showId}/waitlist`, { method: 'POST', body: { seats }, idempotencyKey: newIdempotencyKey() }),
  myWaitlist: () => apiRequest<WaitlistView[]>('/waitlist/me'),
  leaveWaitlist: (entryId: number) => apiRequest<void>(`/waitlist/${entryId}`, { method: 'DELETE' }),
};

export const bookingApi = {
  mine: (page = 0, size = 10) => apiRequest<Paged<BookingView>>('/bookings/me', { query: { page, size } }),
  get: (reference: string) => apiRequest<BookingView>(`/bookings/${reference}`),
  cancel: (reference: string, reason?: string) =>
    apiRequest<BookingView>(`/bookings/${reference}/cancel`, {
      method: 'POST', body: { reason: reason || null }, idempotencyKey: newIdempotencyKey(),
    }),
};

export const adminApi = {
  dashboard: () => apiRequest<Dashboard>('/admin/dashboard'),
  bookings: (page = 0, size = 20) => apiRequest<Paged<AdminBookingView>>('/admin/bookings', { query: { page, size } }),
  movies: () => apiRequest<AdminMovie[]>('/admin/movies'),
  createMovie: (body: MovieRequest) =>
    apiRequest<MovieDetail>('/admin/movies', { method: 'POST', body, idempotencyKey: newIdempotencyKey() }),
  updateMovie: (id: number, body: MovieRequest) => apiRequest<MovieDetail>(`/admin/movies/${id}`, { method: 'PUT', body }),
  cities: () => apiRequest<City[]>('/admin/cities'),
  cinemas: () => apiRequest<CinemaView[]>('/admin/cinemas'),
  createCinema: (body: { cityId: number; name: string; address: string }) =>
    apiRequest<CinemaView>('/admin/cinemas', { method: 'POST', body, idempotencyKey: newIdempotencyKey() }),
  createScreen: (cinemaId: number, body: { name: string; rows: RowSpec[] }) =>
    apiRequest<ScreenView>(`/admin/cinemas/${cinemaId}/screens`, { method: 'POST', body, idempotencyKey: newIdempotencyKey() }),
  shows: (cinemaId: number, date: string) =>
    apiRequest<AdminShowView[]>(`/admin/cinemas/${cinemaId}/shows`, { query: { date } }),
  createShow: (body: ShowRequest) =>
    apiRequest<AdminShowView>('/admin/shows', { method: 'POST', body, idempotencyKey: newIdempotencyKey() }),
  cancelShow: (showId: number, reason: string) =>
    apiRequest<{ showId: number; bookingsCancelled: number }>(`/admin/shows/${showId}/cancel`, {
      method: 'POST', body: { reason }, idempotencyKey: newIdempotencyKey(),
    }),
  notifications: () => apiRequest<NotificationFeed>('/admin/notifications'),
  sendTestEmail: (to?: string) =>
    apiRequest<{ recipient: string; message: string }>('/admin/notifications/test', { method: 'POST', body: { to: to || null } }),
  system: () => apiRequest<SystemSnapshot>('/admin/system'),
};
