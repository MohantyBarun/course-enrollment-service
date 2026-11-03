/** Mirrors the backend DTOs (com.seatsure.dto). Amounts are in paise; instants are ISO-8601 UTC strings. */

export type Role = 'ROLE_CUSTOMER' | 'ROLE_ADMIN';
export type Certificate = 'U' | 'UA' | 'A';
export type ShowFormat = '2D' | '3D' | 'IMAX';
export type SeatCategory = 'RECLINER' | 'PRIME' | 'CLASSIC';
export type SeatStatus = 'AVAILABLE' | 'HELD' | 'BOOKED';
export type Availability = 'AVAILABLE' | 'FILLING_FAST' | 'SOLD_OUT';

// ------------------------------------------------------------------ auth

export interface UserView {
  id: number;
  name: string;
  email: string;
  phone: string | null;
  role: Role;
}

/** Returned by register, login, demo login and refresh. The refresh token rotates on every use. */
export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  user: UserView;
}

// ------------------------------------------------------------------ browsing

export interface City {
  id: number;
  name: string;
  state: string;
}

export interface MovieCard {
  id: number;
  title: string;
  language: string;
  certificate: Certificate;
  genres: string[];
  durationMinutes: number;
  /** yyyy-mm-dd */
  releaseDate: string;
  posterUrl: string | null;
}

export interface MovieDetail extends MovieCard {
  synopsis: string;
}

/** Where customers can find a movie (see the backend's AdminDtos.CustomerVisibility). */
export type CustomerVisibility = 'NOW_SHOWING' | 'COMING_SOON' | 'HIDDEN';

export interface AdminMovie extends MovieDetail {
  visibility: CustomerVisibility;
  upcomingShows: number;
  showingIn: string[];
}

export interface Home {
  city: City;
  nowShowing: MovieCard[];
  comingSoon: MovieCard[];
}

export interface ShowtimeView {
  showId: number;
  startsAt: string;
  format: ShowFormat;
  screenName: string;
  availability: Availability;
  seatsLeft: number;
  minPricePaise: number;
  maxPricePaise: number;
}

export interface CinemaShowtimes {
  cinemaId: number;
  cinemaName: string;
  address: string;
  shows: ShowtimeView[];
}

export interface Showtimes {
  movie: MovieCard;
  cityId: number;
  date: string | null;
  availableDates: string[];
  cinemas: CinemaShowtimes[];
}

export interface PriceView {
  category: SeatCategory;
  pricePaise: number;
}

export interface ShowDetail {
  showId: number;
  movie: MovieCard;
  cinema: { id: number; name: string; address: string; city: string };
  screenName: string;
  startsAt: string;
  endsAt: string;
  format: ShowFormat;
  status: 'SCHEDULED' | 'CANCELLED';
  bookable: boolean;
  prices: PriceView[];
}

export interface SeatView {
  id: number;
  number: number;
  /** 1-based grid column; gaps between columns are aisles. */
  col: number;
  status: SeatStatus;
}

export interface SeatRow {
  label: string;
  category: SeatCategory;
  seats: SeatView[];
}

export interface SeatMap {
  showId: number;
  bookable: boolean;
  maxCol: number;
  available: number;
  total: number;
  prices: PriceView[];
  rows: SeatRow[];
}

/** Payload of the "seats" Server-Sent Event. */
export interface SeatsEvent {
  showId: number;
  changes: { seatId: number; status: SeatStatus }[];
}

// ------------------------------------------------------------------ checkout

export type HoldStatus = 'ACTIVE' | 'CONVERTED' | 'EXPIRED' | 'RELEASED';
export type PaymentStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'REFUNDED';

export interface HeldSeat {
  seatId: number;
  label: string;
  category: SeatCategory;
  pricePaise: number;
}

export interface HoldView {
  holdId: number;
  status: HoldStatus;
  source: 'CUSTOMER' | 'WAITLIST';
  showId: number;
  movieTitle: string;
  cinemaName: string;
  screenName: string;
  startsAt: string;
  format: ShowFormat;
  seats: HeldSeat[];
  ticketsPaise: number;
  convenienceFeePaise: number;
  totalPaise: number;
  expiresAt: string;
  latestPayment: { gatewayRef: string; status: PaymentStatus; failureReason: string | null; updatedAt: string } | null;
  bookingReference: string | null;
}

export interface PaymentStarted {
  gatewayRef: string;
  amountPaise: number;
  status: PaymentStatus;
  checkoutPath: string;
}

export type WaitlistStatus = 'WAITING' | 'OFFERED' | 'FULFILLED' | 'LAPSED' | 'LEFT' | 'CLOSED';

export interface WaitlistView {
  id: number;
  showId: number;
  movieTitle: string;
  cinemaName: string;
  startsAt: string;
  seatsWanted: number;
  status: WaitlistStatus;
  positionInQueue: number | null;
  holdId: number | null;
  joinedAt: string;
}

// ------------------------------------------------------------------ bookings

export interface BookingView {
  reference: string;
  status: 'CONFIRMED' | 'CANCELLED';
  showId: number;
  movieId: number;
  movieTitle: string;
  posterUrl: string | null;
  cinemaName: string;
  cityName: string;
  screenName: string;
  startsAt: string;
  format: ShowFormat;
  seats: { label: string; category: SeatCategory; pricePaise: number }[];
  ticketsPaise: number;
  convenienceFeePaise: number;
  totalPaise: number;
  bookedAt: string;
  cancelledAt: string | null;
  cancelReason: string | null;
  cancellable: boolean;
}

export interface Paged<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

// ------------------------------------------------------------------ admin

export interface MovieRequest {
  title: string;
  synopsis: string;
  durationMinutes: number;
  language: string;
  certificate: Certificate;
  genres: string[];
  releaseDate: string;
  posterUrl: string;
}

export interface ScreenView {
  id: number;
  name: string;
  seatCount: number;
  categories: SeatCategory[];
}

export interface CinemaView {
  id: number;
  name: string;
  address: string;
  cityId: number;
  cityName: string;
  screens: ScreenView[];
}

export interface RowSpec {
  label: string;
  category: SeatCategory;
  seats: number;
  aisleAfter: number[];
}

export interface AdminShowView {
  id: number;
  movieId: number;
  movieTitle: string;
  screenId: number;
  screenName: string;
  startsAt: string;
  endsAt: string;
  format: ShowFormat;
  status: 'SCHEDULED' | 'CANCELLED';
  totalSeats: number;
  bookedSeats: number;
}

export interface ShowRequest {
  movieId: number;
  screenId: number;
  startsAt: string;
  format: ShowFormat;
  pricesPaise: Partial<Record<SeatCategory, number>>;
}

export interface AdminBookingView {
  reference: string;
  customerName: string;
  customerEmail: string;
  movieTitle: string;
  cinemaName: string;
  startsAt: string;
  seats: number;
  totalPaise: number;
  status: string;
  bookedAt: string;
}

export interface Dashboard {
  bookingsToday: number;
  ticketsToday: number;
  revenueTodayPaise: number;
  last7Days: { date: string; bookings: number; tickets: number; revenuePaise: number }[];
  topMovies: { title: string; tickets: number; revenuePaise: number }[];
  busiestUpcomingShows: {
    showId: number; movieTitle: string; cinemaName: string; startsAt: string; booked: number; total: number;
  }[];
  generatedAt: string;
}

export type NotificationStatus = 'QUEUED' | 'RETRYING' | 'IN_BROKER' | 'SENT' | 'LOGGED' | 'PROCESSED' | 'FAILED';

export interface NotificationEntry {
  id: string;
  eventType: string;
  label: string;
  recipient: string | null;
  subject: string;
  preview: string | null;
  status: NotificationStatus;
  detail: string | null;
  attempts: number;
  createdAt: string;
  handledAt: string | null;
  nextAttemptAt: string | null;
}

export interface NotificationFeed {
  emailMode: string;
  transport: string;
  /** Masked; empty when real email isn't restricted to an allowlist. */
  allowedRecipients: string[];
  entries: NotificationEntry[];
}

export interface SystemSnapshot {
  generatedAt: string;
  infrastructure: {
    cache: string; rateLimiter: string; messaging: string; email: string; tracingExport: boolean; demoMode: boolean;
  };
  http: { requests: number; clientErrors: number; serverErrors: number; meanMs: number; maxMs: number };
  caches: { name: string; hits: number; misses: number; hitRate: number | null; size: number | null }[];
  outbox: {
    pending: number; failed: number; published: number; relayedPublished: number; relayedRetries: number; relayedFailed: number;
  };
  rateLimits: { rejectedByRule: Record<string, number>; limitsPerMinute: Record<string, number>; backendErrors: number };
  circuitBreakers: { name: string; state: string; failureRate: number | null; bufferedCalls: number; notPermittedCalls: number }[];
  operations: { name: string; calls: number; failures: number; meanMs: number }[];
  jvm: { uptimeSeconds: number; heapUsedMb: number; heapMaxMb: number; threads: number };
}
