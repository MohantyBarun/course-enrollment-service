import type { ReactNode } from 'react';
import type { AttendanceSummary, GradeLabel } from '../api/types';
import { Spinner } from './ui';

export const ATTENDANCE_MINIMUM = 75;

export function PageHeader({ title, subtitle, action }: { title: string; subtitle?: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="text-2xl font-semibold text-slate-900">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-slate-500">{subtitle}</p>}
      </div>
      {action}
    </div>
  );
}

export function StatCard({ label, value, hint, tone = 'default' }: {
  label: string; value: ReactNode; hint?: ReactNode; tone?: 'default' | 'good' | 'warn' | 'bad';
}) {
  const toneClass = { default: 'text-slate-900', good: 'text-emerald-600', warn: 'text-amber-600', bad: 'text-rose-600' }[tone];
  return (
    <div className="card p-5">
      <p className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</p>
      <p className={`mt-2 text-3xl font-semibold ${toneClass}`}>{value}</p>
      {hint && <p className="mt-1 text-xs text-slate-500">{hint}</p>}
    </div>
  );
}

export function ProgressBar({ value, max = 100, tone }: { value: number; max?: number; tone?: 'good' | 'warn' | 'bad' | 'brand' }) {
  const pct = max <= 0 ? 0 : Math.min(100, Math.round((value / max) * 100));
  const color = { good: 'bg-emerald-500', warn: 'bg-amber-500', bad: 'bg-rose-500', brand: 'bg-indigo-500' }[tone ?? 'brand'];
  return (
    <div className="h-2 w-full overflow-hidden rounded-full bg-slate-100" role="progressbar"
         aria-valuenow={value} aria-valuemin={0} aria-valuemax={max}>
      <div className={`h-full rounded-full ${color} transition-all`} style={{ width: `${pct}%` }} />
    </div>
  );
}

export function attendanceTone(pct: number | null): 'good' | 'warn' | 'bad' | undefined {
  if (pct == null) return undefined;
  if (pct < ATTENDANCE_MINIMUM) return 'bad';
  if (pct < 85) return 'warn';
  return 'good';
}

/** "82.5% (33/40)" with colour and a warning below the minimum. */
export function AttendanceBadge({ attendance }: { attendance: AttendanceSummary }) {
  if (attendance.percentage == null) return <span className="text-xs text-slate-400">No classes yet</span>;
  const tone = attendanceTone(attendance.percentage);
  const cls = { good: 'bg-emerald-50 text-emerald-700', warn: 'bg-amber-50 text-amber-700', bad: 'bg-rose-50 text-rose-700' }[tone!];
  return (
    <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ${cls}`}
          title={`${attendance.classesAttended} of ${attendance.classesHeld} classes`}>
      {tone === 'bad' && <span aria-hidden="true">⚠</span>}
      {attendance.percentage}% <span className="font-normal opacity-75">({attendance.classesAttended}/{attendance.classesHeld})</span>
    </span>
  );
}

export function GradeBadge({ grade }: { grade: GradeLabel | null }) {
  if (!grade) return <span className="text-xs text-slate-400">—</span>;
  const cls = grade === 'F' ? 'bg-rose-50 text-rose-700 ring-rose-200'
    : grade === 'O' || grade === 'A+' ? 'bg-emerald-50 text-emerald-700 ring-emerald-200'
    : 'bg-slate-50 text-slate-700 ring-slate-200';
  return <span className={`inline-flex min-w-[2rem] justify-center rounded-md px-2 py-0.5 text-xs font-semibold ring-1 ${cls}`}>{grade}</span>;
}

export function formatGpa(gpa: number | null | undefined): string {
  return gpa == null ? '—' : gpa.toFixed(2);
}

export function LoadingBlock() {
  return <div className="flex justify-center py-16 text-slate-400"><Spinner className="h-6 w-6" /></div>;
}

export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="px-4 py-12 text-center">
      <p className="font-medium text-slate-900">{title}</p>
      {children && <p className="mt-1 text-sm text-slate-500">{children}</p>}
    </div>
  );
}

export function timeAgo(iso: string | null): string {
  if (!iso) return '';
  const diff = Date.now() - new Date(iso).getTime();
  const days = Math.floor(diff / 86_400_000);
  if (days <= 0) {
    const hours = Math.floor(diff / 3_600_000);
    return hours <= 0 ? 'just now' : `${hours}h ago`;
  }
  return days === 1 ? 'yesterday' : `${days} days ago`;
}
