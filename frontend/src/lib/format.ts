export function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  // Date-only strings (yyyy-mm-dd) are parsed as UTC; format in UTC so the day doesn't shift.
  const date = new Date(iso.length === 10 ? `${iso}T00:00:00Z` : iso);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    ...(iso.length === 10 ? { timeZone: 'UTC' } : {}),
  });
}

export function roleLabel(role: string): string {
  return { ROLE_ADMIN: 'Admin', ROLE_SUPER_ADMIN: 'Super admin', ROLE_STUDENT: 'Student' }[role] ?? role;
}

/** "12s ago", "5m ago", "3h ago", "2d ago"; future times as "in 20s". */
export function relativeTime(iso: string | null | undefined, now: number = Date.now()): string {
  if (!iso) return '—';
  const diff = Math.round((now - new Date(iso).getTime()) / 1000);
  if (Number.isNaN(diff)) return '—';
  const abs = Math.abs(diff);
  const text = abs < 60 ? `${abs}s` : abs < 3600 ? `${Math.floor(abs / 60)}m` : abs < 86_400 ? `${Math.floor(abs / 3600)}h` : `${Math.floor(abs / 86_400)}d`;
  if (abs < 5) return 'just now';
  return diff >= 0 ? `${text} ago` : `in ${text}`;
}

/** 3725 -> "1h 2m"; 59 -> "59s". */
export function formatDuration(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}
