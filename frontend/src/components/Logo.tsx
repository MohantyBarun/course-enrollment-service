import { Link } from 'react-router-dom';

export function Logo({ to = '/' }: { to?: string }) {
  return (
    <Link to={to} className="flex items-center gap-2 rounded-lg focus:outline-none focus-visible:ring-2 focus-visible:ring-sun-400"
          aria-label="SeatSure home">
      <img src="/favicon.svg" alt="" className="h-8 w-8" />
      <span className="text-lg font-extrabold tracking-tight text-white">
        Seat<span className="text-gradient">Sure</span>
      </span>
    </Link>
  );
}
