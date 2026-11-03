import { useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ApiError } from '../api/client';
import { checkoutApi } from '../api/endpoints';
import { Alert, Spinner } from '../components/ui';
import { useApi } from '../hooks/useApi';
import { formatRupees } from '../lib/format';

/**
 * Stands in for a bank's payment page. It's deliberately styled unlike the rest of SeatSure
 * (light, its own brand) because in production this would be the gateway's own site.
 */
export function PayPage() {
  const gatewayRef = useParams().gatewayRef!;
  const holdId = Number(useSearchParams()[0].get('hold'));
  const navigate = useNavigate();
  const hold = useApi(() => (holdId ? checkoutApi.get(holdId) : Promise.resolve(null)), [holdId]);
  const [busy, setBusy] = useState<'SUCCESS' | 'FAILURE' | null>(null);
  const [error, setError] = useState<string | null>(null);

  const submit = async (outcome: 'SUCCESS' | 'FAILURE') => {
    setBusy(outcome);
    setError(null);
    try {
      await checkoutApi.completeMockPayment(gatewayRef, outcome);
      navigate(holdId ? `/checkout/${holdId}` : '/bookings', { replace: true });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'The payment could not be submitted.');
      setBusy(null);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-sky-100 via-white to-indigo-100 px-4 py-10 text-slate-900"
         style={{ colorScheme: 'light' }}>
      <div className="w-full max-w-md overflow-hidden rounded-3xl bg-white shadow-2xl ring-1 ring-slate-200">
        <div className="flex items-center justify-between bg-slate-900 px-6 py-4 text-white">
          <div className="flex items-center gap-2">
            <span className="grid h-8 w-8 place-items-center rounded-lg bg-gradient-to-br from-sky-400 to-indigo-500 text-sm font-black">₹</span>
            <div>
              <p className="text-sm font-bold leading-tight">SeatSure Test Pay</p>
              <p className="text-[11px] text-slate-400">Mock gateway · no real money</p>
            </div>
          </div>
          <span className="rounded-full bg-emerald-500/20 px-2 py-0.5 text-[11px] font-semibold text-emerald-300">🔒 Secure</span>
        </div>

        <div className="space-y-5 p-6">
          <div>
            <p className="text-xs font-medium uppercase tracking-wider text-slate-500">Paying SeatSure</p>
            <p className="text-3xl font-black tracking-tight">
              {hold.data ? formatRupees(hold.data.totalPaise) : '…'}
            </p>
            {hold.data && (
              <p className="text-sm text-slate-500">{hold.data.movieTitle} · {hold.data.seats.map((s) => s.label).join(', ')}</p>
            )}
          </div>

          <div className="space-y-3 rounded-2xl border border-slate-200 bg-slate-50 p-4">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">Card number</p>
              <p className="font-mono text-sm">4242 4242 4242 4242</p>
            </div>
            <div className="flex gap-6">
              <div>
                <p className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">Expiry</p>
                <p className="font-mono text-sm">12 / 34</p>
              </div>
              <div>
                <p className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">CVV</p>
                <p className="font-mono text-sm">•••</p>
              </div>
            </div>
          </div>

          {error && <Alert>{error}</Alert>}

          <div className="space-y-2">
            <button type="button" disabled={busy !== null} onClick={() => submit('SUCCESS')}
                    className="flex w-full items-center justify-center gap-2 rounded-xl bg-indigo-600 py-3 text-sm font-bold text-white shadow-lg shadow-indigo-600/30 hover:bg-indigo-500 disabled:opacity-60">
              {busy === 'SUCCESS' && <Spinner />} Approve payment
            </button>
            <button type="button" disabled={busy !== null} onClick={() => submit('FAILURE')}
                    className="flex w-full items-center justify-center gap-2 rounded-xl border border-slate-300 py-3 text-sm font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-60">
              {busy === 'FAILURE' && <Spinner />} Decline (simulate a failed payment)
            </button>
          </div>
          <p className="text-center text-xs text-slate-500">
            The gateway answers SeatSure with a signed webhook, retried until it's acknowledged.
            {holdId ? <> <Link to={`/checkout/${holdId}`} className="underline">Back to checkout</Link></> : null}
          </p>
        </div>
      </div>
    </div>
  );
}
