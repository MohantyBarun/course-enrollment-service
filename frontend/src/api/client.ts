import { isExpired } from '../lib/jwt';
import { tokenStore } from '../lib/tokens';
import type { TokenResponse } from './types';

export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080').replace(/\/$/, '');

/** Error thrown for any non-2xx response, carrying the backend's message and field errors. */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly fieldErrors: Record<string, string> = {},
    /** The parsed error body, for endpoint-specific details (e.g. unavailableSeatIds on a 409). */
    public readonly body: Record<string, unknown> = {},
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

// ---------------------------------------------------------------------------
// Session-expiry notification (AuthContext listens and signs the user out)
// ---------------------------------------------------------------------------
type Listener = () => void;
const sessionExpiredListeners = new Set<Listener>();
export function onSessionExpired(listener: Listener): () => void {
  sessionExpiredListeners.add(listener);
  return () => sessionExpiredListeners.delete(listener);
}

// ---------------------------------------------------------------------------
// Slow-request tracking: the free hosting tier sleeps when idle, so the first
// request can take up to a minute. The UI shows a notice while this is > 0.
// ---------------------------------------------------------------------------
const SLOW_REQUEST_MS = 4000;
let slowRequests = 0;
const slowListeners = new Set<(count: number) => void>();
export function onSlowRequestsChange(listener: (count: number) => void): () => void {
  slowListeners.add(listener);
  return () => slowListeners.delete(listener);
}
function setSlow(delta: number) {
  slowRequests = Math.max(0, slowRequests + delta);
  slowListeners.forEach((l) => l(slowRequests));
}

async function withSlowTracking<T>(work: Promise<T>): Promise<T> {
  let flagged = false;
  const timer = setTimeout(() => {
    flagged = true;
    setSlow(1);
  }, SLOW_REQUEST_MS);
  try {
    return await work;
  } finally {
    clearTimeout(timer);
    if (flagged) setSlow(-1);
  }
}

// ---------------------------------------------------------------------------
// Token refresh: single-flight, so concurrent requests share one refresh call
// ---------------------------------------------------------------------------
let refreshInFlight: Promise<string> | null = null;

async function refreshAccessToken(): Promise<string> {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      const tokens = tokenStore.get();
      if (!tokens) throw new ApiError(401, 'Not signed in');
      const response = await withSlowTracking(
        fetch(`${API_BASE_URL}/auth/refresh`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken: tokens.refreshToken }),
        }),
      );
      if (!response.ok) {
        tokenStore.clear();
        sessionExpiredListeners.forEach((l) => l());
        throw new ApiError(401, 'Your session has expired. Please sign in again.');
      }
      // Refresh tokens rotate: the old one is now dead, so store the new pair before anything else runs
      const pair = (await response.json()) as TokenResponse;
      tokenStore.set({ accessToken: pair.accessToken, refreshToken: pair.refreshToken });
      return pair.accessToken;
    })().finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

async function validAccessToken(): Promise<string | null> {
  const tokens = tokenStore.get();
  if (!tokens) return null;
  return isExpired(tokens.accessToken) ? refreshAccessToken() : tokens.accessToken;
}

// ---------------------------------------------------------------------------
// Request helper
// ---------------------------------------------------------------------------
interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  body?: unknown;
  /** Attach the access token (default true). */
  auth?: boolean;
  query?: Record<string, string | number | undefined>;
  /**
   * Sent as the Idempotency-Key header. The server then runs the request at most once per key, so a
   * request that fails on the network (e.g. while the free-tier server wakes up) is retried safely.
   */
  idempotencyKey?: string;
}

/** A fresh key per user action (one form submit, one Pay click). */
export function newIdempotencyKey(): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') return globalThis.crypto.randomUUID();
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;
}

/** Waits before retrying an idempotent request whose response never arrived. */
export const NETWORK_RETRY_DELAYS_MS = [1000, 3000];
const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

async function parseError(response: Response): Promise<ApiError> {
  let message = `Request failed (${response.status})`;
  let fieldErrors: Record<string, string> = {};
  let body: Record<string, unknown> = {};
  try {
    const data = await response.json();
    if (data && typeof data === 'object') body = data;
    if (data?.errors && typeof data.errors === 'object') {
      fieldErrors = data.errors;
      message = 'Please correct the highlighted fields.';
    } else if (typeof data?.error === 'string') {
      message = data.error;
    }
  } catch {
    // Non-JSON error body (e.g. a proxy page)
  }
  if (response.status === 403 && message.startsWith('Request failed')) {
    message = "You don't have permission to do that.";
  }
  return new ApiError(response.status, message, fieldErrors, body);
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, auth = true, query, idempotencyKey } = options;

  const url = new URL(`${API_BASE_URL}${path}`);
  Object.entries(query ?? {}).forEach(([key, value]) => {
    if (value !== undefined && value !== '') url.searchParams.set(key, String(value));
  });

  const headers: Record<string, string> = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (auth) {
    const token = await validAccessToken();
    if (token) headers.Authorization = `Bearer ${token}`;
  }
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;

  let response: Response | undefined;
  for (let attempt = 0; response === undefined; attempt++) {
    try {
      response = await withSlowTracking(
        fetch(url, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined }),
      );
    } catch {
      // Only requests with a key are safe to resend: the server won't run them twice
      if (!idempotencyKey || attempt >= NETWORK_RETRY_DELAYS_MS.length) {
        throw new ApiError(0, 'Cannot reach the server. Check your connection and try again.');
      }
      await sleep(NETWORK_RETRY_DELAYS_MS[attempt]);
    }
  }

  if (!response.ok) throw await parseError(response);
  if (response.status === 204) return undefined as T;

  const contentType = response.headers.get('Content-Type') ?? '';
  return (contentType.includes('application/json') ? response.json() : response.text()) as Promise<T>;
}
