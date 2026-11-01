import { useEffect, useState } from 'react';

/** Seconds left until `deadline` (ISO instant), ticking every second; 0 once it has passed. */
export function useCountdown(deadline: string | null | undefined): number {
  const compute = () => (deadline ? Math.max(0, Math.round((new Date(deadline).getTime() - Date.now()) / 1000)) : 0);
  const [left, setLeft] = useState(compute);

  useEffect(() => {
    setLeft(compute());
    if (!deadline) return;
    const id = setInterval(() => setLeft(compute()), 1000);
    return () => clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deadline]);

  return left;
}
