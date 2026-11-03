/** Shows are scheduled in India, so times are shown in IST whatever the viewer's own time zone. */
export const ZONE = 'Asia/Kolkata';

const wholeRupees = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 });
const exactRupees = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', minimumFractionDigits: 2 });

/** 25000 -> "₹250"; 25050 -> "₹250.50" (never "₹250.5"). */
export function formatRupees(paise: number): string {
  return (paise % 100 === 0 ? wholeRupees : exactRupees).format(paise / 100);
}

/** "8:45 PM" */
export function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString('en-IN', { hour: 'numeric', minute: '2-digit', hour12: true, timeZone: ZONE })
    .toUpperCase();
}

/** "Sat, 1 Nov" */
export function formatShortDate(iso: string): string {
  return new Date(iso).toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short', timeZone: ZONE });
}

/** "Sat, 1 Nov · 8:45 PM" */
export function formatShowTime(iso: string): string {
  return `${formatShortDate(iso)} · ${formatTime(iso)}`;
}

/** The IST calendar date (yyyy-mm-dd) of an instant. */
export function istDate(iso: string | number | Date): string {
  // en-CA formats as yyyy-mm-dd
  return new Date(iso).toLocaleDateString('en-CA', { timeZone: ZONE });
}

/** Today's date in IST, yyyy-mm-dd. */
export function todayIst(now: number = Date.now()): string {
  return istDate(now);
}

/** Adds days to a yyyy-mm-dd date. */
export function addDays(date: string, days: number): string {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** Parts of a yyyy-mm-dd date for the date strip: { weekday: "SAT", day: "1", month: "NOV" }. */
export function dateParts(date: string): { weekday: string; day: string; month: string } {
  const d = new Date(`${date}T00:00:00Z`);
  const opt = (o: Intl.DateTimeFormatOptions) => d.toLocaleDateString('en-IN', { ...o, timeZone: 'UTC' }).toUpperCase();
  return { weekday: opt({ weekday: 'short' }), day: String(d.getUTCDate()), month: opt({ month: 'short' }) };
}

/** "1 Nov 2026" for a yyyy-mm-dd date. */
export function formatDate(date: string): string {
  return new Date(`${date}T00:00:00Z`).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });
}

/** 152 -> "2h 32m" */
export function formatRuntime(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h ? `${h}h${m ? ` ${m}m` : ''}` : `${m}m`;
}

/** 3725 -> "1h 2m"; 59 -> "59s". */
export function formatDuration(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

/** 545 -> "09:05" */
export function formatCountdown(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
}

/** "12s ago", "5m ago", "3h ago", "2d ago"; future times as "in 20s". */
export function relativeTime(iso: string | null | undefined, now: number = Date.now()): string {
  if (!iso) return '—';
  const diff = Math.round((now - new Date(iso).getTime()) / 1000);
  if (Number.isNaN(diff)) return '—';
  const abs = Math.abs(diff);
  if (abs < 5) return 'just now';
  const text = abs < 60 ? `${abs}s` : abs < 3600 ? `${Math.floor(abs / 60)}m` : abs < 86_400 ? `${Math.floor(abs / 3600)}h` : `${Math.floor(abs / 86_400)}d`;
  return diff >= 0 ? `${text} ago` : `in ${text}`;
}

export const CATEGORY_LABEL = { RECLINER: 'Recliner', PRIME: 'Prime', CLASSIC: 'Classic' } as const;

/** Day label for the date strip relative to today: "Today", "Tomorrow" or the weekday. */
export function dayLabel(date: string, today: string = todayIst()): string {
  if (date === today) return 'TODAY';
  if (date === addDays(today, 1)) return 'TMRW';
  return dateParts(date).weekday;
}

export function pluralize(n: number, word: string, plural = `${word}s`): string {
  return `${n} ${n === 1 ? word : plural}`;
}
