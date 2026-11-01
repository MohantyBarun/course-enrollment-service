import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import type { NotificationFeed, SystemSnapshot } from '../api/types';
import { formatDuration, relativeTime } from '../lib/format';
import { jsonResponse, mockFetch, renderAt, signIn } from '../test/helpers';
import { EmailsPage } from './EmailsPage';
import { SystemPage } from './SystemPage';

const now = new Date().toISOString();

const feed: NotificationFeed = {
  emailMode: 'Logged only (demo mode; set MAIL_ALLOWED_RECIPIENTS to email chosen addresses)',
  transport: 'In-process',
  allowedRecipients: [],
  entries: [
    { id: 'a1', eventType: 'student.registered', label: 'Welcome email with temporary password',
      recipient: 'asha@college.test', subject: 'Your StudentHub account',
      preview: 'Temporary password: ••••••••', status: 'LOGGED', detail: 'Not sent: demo mode', attempts: 0,
      createdAt: now, handledAt: now, nextAttemptAt: null },
    { id: 'b2', eventType: 'enrollment.confirmed', label: 'Enrollment confirmation', recipient: 'ravi@college.test',
      subject: 'Enrollment confirmation', preview: null, status: 'RETRYING', detail: 'Attempt 2 failed: SMTP timeout',
      attempts: 2, createdAt: now, handledAt: null, nextAttemptAt: now },
  ],
};

const snapshot: SystemSnapshot = {
  generatedAt: now,
  infrastructure: { cache: 'Caffeine (in-process)', rateLimiter: 'In-memory (single instance)',
    messaging: 'Outbox -> in-process consumer', email: 'Logged only (demo mode)', tracingExport: false, demoMode: true },
  http: { requests: 120, clientErrors: 4, serverErrors: 0, meanMs: 18.5, maxMs: 240 },
  caches: [{ name: 'courseCatalog', hits: 30, misses: 10, hitRate: 75, size: 3 }],
  outbox: { pending: 1, failed: 0, published: 40, relayedPublished: 40, relayedRetries: 2, relayedFailed: 0 },
  rateLimits: { rejectedByRule: { login: 0, api: 0 }, limitsPerMinute: { login: 3, api: 300 }, backendErrors: 0 },
  circuitBreakers: [{ name: 'smtp', state: 'CLOSED', failureRate: null, bufferedCalls: 0, notPermittedCalls: 0 }],
  operations: [{ name: 'Logins', calls: 12, failures: 2, meanMs: 95 }, { name: 'Enrollments', calls: 5, failures: 1, meanMs: 30 }],
  jvm: { uptimeSeconds: 3725, heapUsedMb: 120, heapMaxMb: 384, threads: 31 },
};

describe('EmailsPage', () => {
  it('lists notifications with their pipeline status and explains logged-only mode', async () => {
    signIn({ role: 'ROLE_ADMIN' });
    mockFetch((url) => (url.pathname === '/admin/notifications' ? jsonResponse(feed) : jsonResponse({})));
    renderAt('/emails', <Route path="/emails" element={<EmailsPage />} />);

    expect(await screen.findByText('Your StudentHub account')).toBeInTheDocument();
    expect(screen.getByText('Logged only')).toBeInTheDocument();
    expect(screen.getByText('Retrying')).toBeInTheDocument();
    expect(screen.getByText('2 failed attempts')).toBeInTheDocument();
    expect(screen.getByText(/Real email is off: demo mode/)).toBeInTheDocument();
    expect(screen.getByText('Temporary password: ••••••••')).toBeInTheDocument();
    expect(screen.getByText('In-process')).toBeInTheDocument();
  });

  it('shows an empty state', async () => {
    signIn({ role: 'ROLE_ADMIN' });
    mockFetch(() => jsonResponse({ ...feed, emailMode: 'SMTP', entries: [] }));
    renderAt('/emails', <Route path="/emails" element={<EmailsPage />} />);

    expect(await screen.findByText('No emails yet')).toBeInTheDocument();
    expect(screen.queryByText(/Real email is off/)).not.toBeInTheDocument();
  });
});

describe('EmailsPage with an allowlist', () => {
  it('says who gets real email, shows why others were not sent, and queues a test email', async () => {
    signIn({ role: 'ROLE_ADMIN' });
    const allowlisted: NotificationFeed = {
      ...feed, emailMode: 'SMTP for 1 allowlisted recipient; others logged only', allowedRecipients: ['ow***@gmail.com'],
      entries: [{ ...feed.entries[0], detail: "Not sent: recipient isn't on MAIL_ALLOWED_RECIPIENTS" }],
    };
    const fetchMock = mockFetch((url, init) => {
      if (url.pathname === '/admin/notifications/test' && init.method === 'POST') {
        return jsonResponse({ recipient: 'ow***@gmail.com', message: 'queued' }, 202);
      }
      return jsonResponse(allowlisted);
    });
    renderAt('/emails', <Route path="/emails" element={<EmailsPage />} />);

    expect(await screen.findByText('ow***@gmail.com')).toBeInTheDocument();
    expect(screen.getByText(/emails to anyone else are logged only/)).toBeInTheDocument();
    expect(screen.getByText("Not sent: recipient isn't on MAIL_ALLOWED_RECIPIENTS")).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Send test email' }));

    expect(await screen.findByText(/Test email to ow\*\*\*@gmail.com queued/)).toBeInTheDocument();
    const call = fetchMock.mock.calls.find(([u]) => String(u).endsWith('/admin/notifications/test'))!;
    expect(JSON.parse(String(call[1]!.body))).toEqual({ to: null });
  });

  it('shows the server message when a test email is refused', async () => {
    signIn({ role: 'ROLE_ADMIN' });
    mockFetch((url, init) => (url.pathname === '/admin/notifications/test' && init.method === 'POST'
      ? jsonResponse({ error: 'A test email was sent 30 seconds ago. Try again in 5 minutes.' }, 409)
      : jsonResponse({ ...feed, allowedRecipients: ['ow***@gmail.com'] })));
    renderAt('/emails', <Route path="/emails" element={<EmailsPage />} />);

    await userEvent.type(await screen.findByLabelText('Send a test email'), 'owner@gmail.com');
    await userEvent.click(screen.getByRole('button', { name: 'Send test email' }));

    expect(await screen.findByText(/Try again in 5 minutes/)).toBeInTheDocument();
  });

  it('explains a missing endpoint as a backend that is still deploying', async () => {
    signIn({ role: 'ROLE_ADMIN' });
    mockFetch(() => jsonResponse({ error: 'Endpoint not found: /admin/notifications' }, 404));
    renderAt('/emails', <Route path="/emails" element={<EmailsPage />} />);

    expect(await screen.findByText(/still deploying/)).toBeInTheDocument();
  });
});

describe('SystemPage', () => {
  it('shows live traffic, caches, outbox, breakers and the infrastructure', async () => {
    signIn({ role: 'ROLE_ADMIN' });
    mockFetch((url) => (url.pathname === '/admin/system' ? jsonResponse(snapshot) : jsonResponse({})));
    renderAt('/system', <Route path="/system" element={<SystemPage />} />);

    expect(await screen.findByText('120')).toBeInTheDocument();
    expect(screen.getByText('18.5 ms')).toBeInTheDocument();
    expect(screen.getByText('1h 2m')).toBeInTheDocument();
    expect(screen.getByText(/75% hits/)).toBeInTheDocument();
    expect(screen.getByText('Caffeine (in-process)')).toBeInTheDocument();
    expect(screen.getByText('CLOSED')).toBeInTheDocument();
    expect(screen.getByText('Logins')).toBeInTheDocument();
  });

  it('runs the rate-limit probe and shows when requests start getting 429', async () => {
    signIn({ role: 'ROLE_ADMIN' });
    let logins = 0;
    const fetchMock = mockFetch((url) => {
      if (url.pathname === '/auth/login') {
        logins += 1;
        return logins <= 3 ? jsonResponse({ error: 'No account found' }, 404) : jsonResponse({ error: 'Too many requests' }, 429);
      }
      return jsonResponse(snapshot);
    });
    renderAt('/system', <Route path="/system" element={<SystemPage />} />);

    await userEvent.click(await screen.findByRole('button', { name: 'Send 5 wrong logins' }));

    const results = await screen.findByLabelText('Probe results');
    await waitFor(() => expect(within(results).getByText(/3 attempts got through/)).toBeInTheDocument());
    expect(within(results).getAllByText('429')).toHaveLength(2);
    const loginCalls = fetchMock.mock.calls.filter(([u]) => String(u).endsWith('/auth/login'));
    expect(loginCalls).toHaveLength(5);
    expect((loginCalls[0][1]!.headers as Record<string, string>).Authorization).toBeUndefined();
  });
});

describe('format helpers', () => {
  it('formats relative times and durations', () => {
    const base = Date.parse('2026-11-01T10:00:00Z');
    expect(relativeTime('2026-11-01T09:59:58Z', base)).toBe('just now');
    expect(relativeTime('2026-11-01T09:59:30Z', base)).toBe('30s ago');
    expect(relativeTime('2026-11-01T09:55:00Z', base)).toBe('5m ago');
    expect(relativeTime('2026-11-01T10:00:20Z', base)).toBe('in 20s');
    expect(relativeTime(null, base)).toBe('—');
    expect(formatDuration(59)).toBe('59s');
    expect(formatDuration(600)).toBe('10m');
    expect(formatDuration(3725)).toBe('1h 2m');
  });
});
