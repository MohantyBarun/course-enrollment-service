import { describe, expect, it, vi } from 'vitest';
import { tokenStore } from '../lib/tokens';
import { fakeJwt, jsonResponse, mockFetch, signIn } from '../test/helpers';
import { ApiError, apiRequest, NETWORK_RETRY_DELAYS_MS, newIdempotencyKey, onSessionExpired } from './client';

describe('apiRequest', () => {
  it('sends an Idempotency-Key and retries a network failure with the same key', async () => {
    vi.useFakeTimers();
    try {
      signIn();
      let calls = 0;
      const fetchMock = mockFetch(() => {
        calls += 1;
        if (calls === 1) throw new TypeError('Failed to fetch');
        return jsonResponse({ id: 1 }, 201);
      });

      const pending = apiRequest('/students', { method: 'POST', body: {}, idempotencyKey: 'key-12345678' });
      await vi.advanceTimersByTimeAsync(NETWORK_RETRY_DELAYS_MS[0]);
      await expect(pending).resolves.toEqual({ id: 1 });

      const keys = fetchMock.mock.calls.map(([, init]) => (init!.headers as Record<string, string>)['Idempotency-Key']);
      expect(keys).toEqual(['key-12345678', 'key-12345678']);
    } finally {
      vi.useRealTimers();
    }
  });

  it('does not retry a network failure without an Idempotency-Key', async () => {
    signIn();
    const fetchMock = mockFetch(() => {
      throw new TypeError('Failed to fetch');
    });

    await expect(apiRequest('/students', { method: 'POST', body: {} })).rejects.toMatchObject({ status: 0 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('generates distinct idempotency keys', () => {
    expect(newIdempotencyKey()).not.toBe(newIdempotencyKey());
    expect(newIdempotencyKey().length).toBeGreaterThanOrEqual(8);
  });

  it('attaches the access token as a Bearer header', async () => {
    signIn();
    const fetchMock = mockFetch(() => jsonResponse({ ok: true }));

    await apiRequest('/students');

    const headers = fetchMock.mock.calls[0][1]!.headers as Record<string, string>;
    expect(headers.Authorization).toBe(`Bearer ${tokenStore.get()!.accessToken}`);
  });

  it('omits empty query parameters', async () => {
    signIn();
    const fetchMock = mockFetch(() => jsonResponse({}));

    await apiRequest('/students', { query: { search: '', page: 0, branch: undefined } });

    const url = new URL(String(fetchMock.mock.calls[0][0]));
    expect(url.searchParams.get('page')).toBe('0');
    expect(url.searchParams.has('search')).toBe(false);
    expect(url.searchParams.has('branch')).toBe(false);
  });

  it('refreshes an expired access token before the request, once for concurrent calls', async () => {
    signIn({}, -10); // already expired
    const fresh = fakeJwt({}, 900);
    const fetchMock = mockFetch((url) =>
      url.pathname === '/auth/refresh' ? jsonResponse({ accessToken: fresh }) : jsonResponse({ ok: true }));

    await Promise.all([apiRequest('/students'), apiRequest('/students/1')]);

    const refreshCalls = fetchMock.mock.calls.filter(([u]) => String(u).endsWith('/auth/refresh'));
    expect(refreshCalls).toHaveLength(1);
    expect(tokenStore.get()!.accessToken).toBe(fresh);
    const lastHeaders = fetchMock.mock.calls.at(-1)![1]!.headers as Record<string, string>;
    expect(lastHeaders.Authorization).toBe(`Bearer ${fresh}`);
  });

  it('signs out and notifies listeners when the refresh token is rejected', async () => {
    signIn({}, -10);
    mockFetch(() => jsonResponse({ error: 'Refresh token has been revoked' }, 401));
    const listener = vi.fn();
    const unsubscribe = onSessionExpired(listener);

    await expect(apiRequest('/students')).rejects.toMatchObject({ status: 401 });
    expect(tokenStore.get()).toBeNull();
    expect(listener).toHaveBeenCalledOnce();
    unsubscribe();
  });

  it('turns backend validation errors into field errors', async () => {
    mockFetch(() => jsonResponse({ status: 400, errors: { email: 'Invalid format of email' } }, 400));

    const error = (await apiRequest('/auth/login', { method: 'POST', body: {}, auth: false }).catch((e: unknown) => e)) as ApiError;

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(400);
    expect(error.fieldErrors).toEqual({ email: 'Invalid format of email' });
  });

  it('surfaces the backend error message', async () => {
    mockFetch(() => jsonResponse({ error: 'Account temporarily blocked' }, 403));

    await expect(apiRequest('/auth/login', { method: 'POST', body: {}, auth: false }))
      .rejects.toThrow('Account temporarily blocked');
  });

  it('reports network failures clearly', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))));

    await expect(apiRequest('/students', { auth: false })).rejects.toMatchObject({ status: 0 });
  });

  it('handles 204 No Content and plain-text responses', async () => {
    signIn();
    mockFetch((url) => (url.pathname === '/students/1'
      ? new Response(null, { status: 204 })
      : new Response('Logged out successfully', { status: 200, headers: { 'Content-Type': 'text/plain' } })));

    await expect(apiRequest('/students/1', { method: 'DELETE' })).resolves.toBeUndefined();
    await expect(apiRequest('/auth/logout', { method: 'POST', body: {} })).resolves.toBe('Logged out successfully');
  });
});
