import { useEffect, useId, useRef, type InputHTMLAttributes, type ReactNode } from 'react';

export function Spinner({ className = 'h-4 w-4' }: { className?: string }) {
  return (
    <svg className={`animate-spin ${className}`} viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 0 1 8-8v4a4 4 0 0 0-4 4H4Z" />
    </svg>
  );
}

export function Alert({ kind = 'error', children }: { kind?: 'error' | 'success' | 'info' | 'warn'; children: ReactNode }) {
  const styles = {
    error: 'border-flame-500/30 bg-flame-500/10 text-flame-300',
    success: 'border-mint-400/30 bg-mint-400/10 text-mint-300',
    info: 'border-grape-400/30 bg-grape-500/10 text-ink-100',
    warn: 'border-sun-400/30 bg-sun-400/10 text-sun-300',
  }[kind];
  return (
    <div role={kind === 'error' ? 'alert' : 'status'} className={`rounded-xl border px-4 py-3 text-sm ${styles}`}>
      {children}
    </div>
  );
}

interface FieldProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string;
  error?: string;
  hint?: ReactNode;
}

/** Labelled input with accessible error messaging. */
export function Field({ label, error, hint, className = '', ...inputProps }: FieldProps) {
  const id = useId();
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined;
  return (
    <div className={className}>
      <label htmlFor={id} className="label">{label}</label>
      <input id={id} className={`input ${error ? 'input-error' : ''}`} aria-invalid={Boolean(error)}
             aria-describedby={describedBy} {...inputProps} />
      {error ? (
        <p id={`${id}-error`} className="mt-1 text-xs text-flame-300">{error}</p>
      ) : hint ? (
        <p id={`${id}-hint`} className="mt-1 text-xs text-ink-400">{hint}</p>
      ) : null}
    </div>
  );
}

interface ModalProps {
  title: string;
  open: boolean;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
  wide?: boolean;
}

/** Accessible dialog: Escape closes it, focus moves inside, background clicks close it. */
export function Modal({ title, open, onClose, children, footer, wide }: ModalProps) {
  const panelRef = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const closeRef = useRef(onClose);
  closeRef.current = onClose;

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && closeRef.current();
    document.addEventListener('keydown', onKey);
    const previouslyFocused = document.activeElement as HTMLElement | null;
    panelRef.current?.querySelector<HTMLElement>('input, select, textarea, [data-autofocus], button')?.focus();
    return () => {
      document.removeEventListener('keydown', onKey);
      previouslyFocused?.focus();
    };
  }, [open]);

  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/60 p-4 backdrop-blur-sm sm:items-center"
         onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div ref={panelRef} role="dialog" aria-modal="true" aria-labelledby={titleId}
           className={`card w-full animate-fade-up ${wide ? 'max-w-2xl' : 'max-w-md'} max-h-[90vh] overflow-y-auto bg-ink-900`}>
        <div className="flex items-center justify-between border-b border-white/5 px-6 py-4">
          <h2 id={titleId} className="text-base font-bold text-white">{title}</h2>
          <button type="button" onClick={onClose} className="btn-ghost" aria-label="Close dialog">✕</button>
        </div>
        <div className="px-6 py-5">{children}</div>
        {footer && <div className="flex justify-end gap-3 border-t border-white/5 px-6 py-4">{footer}</div>}
      </div>
    </div>
  );
}

export function EmptyState({ icon = '🎬', title, children }: { icon?: string; title: string; children?: ReactNode }) {
  return (
    <div className="flex flex-col items-center px-6 py-14 text-center">
      <div className="mb-4 grid h-16 w-16 place-items-center rounded-2xl bg-white/5 text-3xl" aria-hidden="true">{icon}</div>
      <p className="text-base font-semibold text-white">{title}</p>
      {children && <div className="mt-1 max-w-md text-sm text-ink-400">{children}</div>}
    </div>
  );
}

export function Skeleton({ className = '' }: { className?: string }) {
  return <div className={`animate-pulse rounded-xl bg-white/[0.06] ${className}`} />;
}

export function LoadingBlock({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="flex items-center justify-center gap-2 py-16 text-sm text-ink-400" role="status">
      <Spinner /> {label}
    </div>
  );
}

export function PageHeader({ title, subtitle, action }: { title: string; subtitle?: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="text-2xl font-extrabold tracking-tight text-white sm:text-3xl">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-ink-400">{subtitle}</p>}
      </div>
      {action}
    </div>
  );
}

const TONES = {
  mint: 'bg-mint-400/15 text-mint-300 ring-mint-400/30',
  sun: 'bg-sun-400/15 text-sun-300 ring-sun-400/30',
  flame: 'bg-flame-500/15 text-flame-300 ring-flame-500/30',
  grape: 'bg-grape-500/15 text-grape-400 ring-grape-400/30',
  ink: 'bg-white/5 text-ink-300 ring-white/10',
} as const;
export type Tone = keyof typeof TONES;

export function Badge({ tone = 'ink', children, title }: { tone?: Tone; children: ReactNode; title?: string }) {
  return (
    <span title={title} className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold ring-1 ${TONES[tone]}`}>
      {children}
    </span>
  );
}

export function StatCard({ label, value, hint, accent = 'from-flame-500 to-sun-400' }: {
  label: string; value: ReactNode; hint?: ReactNode; accent?: string;
}) {
  return (
    <div className="card relative overflow-hidden p-5">
      <div className={`absolute inset-x-0 top-0 h-1 bg-gradient-to-r ${accent}`} aria-hidden="true" />
      <p className="text-xs font-semibold uppercase tracking-wider text-ink-400">{label}</p>
      <p className="mt-2 text-2xl font-extrabold text-white">{value}</p>
      {hint && <p className="mt-1 text-xs text-ink-400">{hint}</p>}
    </div>
  );
}

export function ProgressBar({ value, max = 100, tone = 'brand' }: { value: number; max?: number; tone?: 'brand' | 'good' | 'bad' }) {
  const pct = max > 0 ? Math.min(100, Math.max(0, (value / max) * 100)) : 0;
  const color = { brand: 'bg-marquee', good: 'bg-mint-400', bad: 'bg-flame-500' }[tone];
  return (
    <div className="h-2 overflow-hidden rounded-full bg-white/[0.06]" role="progressbar" aria-valuenow={Math.round(pct)}
         aria-valuemin={0} aria-valuemax={100}>
      <div className={`h-full rounded-full ${color} transition-all`} style={{ width: `${pct}%` }} />
    </div>
  );
}
