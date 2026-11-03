import { useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { ApiError } from '../api/client';
import { checkoutApi } from '../api/endpoints';
import type { WaitlistStatus, WaitlistView } from '../api/types';
import { Alert, Badge, EmptyState, LoadingBlock, PageHeader, type Tone } from '../components/ui';
import { useApi } from '../hooks/useApi';
import { usePolling } from '../hooks/usePolling';
import { explainApiError } from '../lib/backendErrors';
import { formatShowTime, pluralize } from '../lib/format';

const STATUS: Record<WaitlistStatus, { label: string; tone: Tone }> = {
  WAITING: { label: 'In line', tone: 'grape' },
  OFFERED: { label: 'Seats held for you!', tone: 'sun' },
  FULFILLED: { label: 'Booked', tone: 'mint' },
  LAPSED: { label: 'Offer expired', tone: 'ink' },
  LEFT: { label: 'Left', tone: 'ink' },
  CLOSED: { label: 'Show closed', tone: 'ink' },
};

function Entry({ entry, onLeave }: { entry: WaitlistView; onLeave: (id: number) => Promise<void> }) {
  const s = STATUS[entry.status];
  return (
    <li className={`card p-5 ${entry.status === 'OFFERED' ? 'ring-2 ring-sun-400/60' : ''}`}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <Link to={`/shows/${entry.showId}`} className="font-bold text-white hover:underline">{entry.movieTitle}</Link>
          <p className="text-sm text-ink-300">{entry.cinemaName}</p>
          <p className="text-sm font-semibold text-sun-300">{formatShowTime(entry.startsAt)}</p>
        </div>
        <Badge tone={s.tone}>{s.label}</Badge>
      </div>
      <div className="mt-4 flex flex-wrap items-center gap-3 text-sm text-ink-300">
        <span>Wants {pluralize(entry.seatsWanted, 'seat')}</span>
        {entry.status === 'WAITING' && entry.positionInQueue != null && (
          <span className="chip bg-grape-500/20 text-grape-400">#{entry.positionInQueue} in line</span>
        )}
        <span className="ml-auto flex gap-2">
          {entry.status === 'OFFERED' && entry.holdId && (
            <Link to={`/checkout/${entry.holdId}`} className="btn-primary px-4 py-2">Pay now</Link>
          )}
          {(entry.status === 'WAITING' || entry.status === 'OFFERED') && (
            <button type="button" className="btn-ghost" onClick={() => onLeave(entry.id)}>Leave</button>
          )}
        </span>
      </div>
    </li>
  );
}

export function WaitlistPage() {
  const joined = Boolean((useLocation().state as { joined?: boolean } | null)?.joined);
  const list = useApi(checkoutApi.myWaitlist);
  usePolling(list.reload, 10_000);
  const [leaveError, setLeaveError] = useState<string | null>(null);
  const error = explainApiError(list.error);

  const leave = async (id: number) => {
    setLeaveError(null);
    try {
      await checkoutApi.leaveWaitlist(id);
      await list.reload();
    } catch (e) {
      setLeaveError(e instanceof ApiError ? e.message : 'Could not leave the waitlist.');
    }
  };

  return (
    <div className="mx-auto max-w-3xl space-y-6 px-4 py-8 sm:px-6">
      <PageHeader title="My waitlist" subtitle="Sold-out shows you're waiting on. We email you the moment seats are held for you." />
      {joined && <Alert kind="success">You're on the waitlist. We'll email you if seats free up.</Alert>}
      {error && <Alert>{error}</Alert>}
      {leaveError && <Alert>{leaveError}</Alert>}
      {!list.data ? (!error && <LoadingBlock />) : list.data.length === 0 ? (
        <div className="card"><EmptyState icon="⏳" title="You're not waiting on any show">
          When a show sells out, you can join its waitlist from the seat map.
        </EmptyState></div>
      ) : (
        <ul className="space-y-3">{list.data.map((e) => <Entry key={e.id} entry={e} onLeave={leave} />)}</ul>
      )}
    </div>
  );
}
