import { memo } from 'react';
import type { SeatMap, SeatView } from '../api/types';
import { CATEGORY_LABEL, formatRupees } from '../lib/format';
import { categoryBlocks, priceOf } from '../lib/seatMap';

const SEAT_BASE = 'relative grid h-7 w-7 place-items-center rounded-t-lg rounded-b-md text-[10px] font-bold transition sm:h-8 sm:w-8';

export function seatClass(status: SeatView['status'], selected: boolean): string {
  if (selected) return `${SEAT_BASE} bg-marquee text-ink-950 shadow-glow scale-105`;
  switch (status) {
    case 'AVAILABLE':
      return `${SEAT_BASE} border border-mint-400/60 text-mint-300 hover:bg-mint-400/20 hover:-translate-y-0.5`;
    case 'HELD':
      return `${SEAT_BASE} cursor-not-allowed border border-sun-400/30 bg-[repeating-linear-gradient(45deg,rgba(255,195,92,0.25)_0_3px,transparent_3px_6px)] text-transparent`;
    default:
      return `${SEAT_BASE} cursor-not-allowed bg-ink-700 text-transparent`;
  }
}

interface Props {
  map: SeatMap;
  selected: Set<number>;
  onToggle: (seat: SeatView, rowLabel: string) => void;
  /** Seat ids that just changed from a live update, briefly highlighted. */
  flashing?: Set<number>;
}

/** The hall from the back row to the front, with the screen at the bottom. */
export const SeatGrid = memo(function SeatGrid({ map, selected, onToggle, flashing }: Props) {
  const columns = `repeat(${map.maxCol}, minmax(0, max-content))`;
  return (
    <div className="scrollbar-none overflow-x-auto pb-2">
      <div className="mx-auto w-max space-y-6 px-2">
        {categoryBlocks(map).map((block, i) => (
          <div key={`${block.category}-${i}`} role="group" aria-label={`${CATEGORY_LABEL[block.category]} seats`}>
            <p className="mb-2 border-b border-white/5 pb-1.5 text-center text-xs font-semibold uppercase tracking-widest text-ink-400">
              {CATEGORY_LABEL[block.category]} · <span className="text-sun-300">{formatRupees(priceOf(map.prices, block.category))}</span>
            </p>
            <div className="space-y-1.5">
              {block.rows.map((row) => (
                <div key={row.label} className="flex items-center gap-3">
                  <span className="w-5 text-right text-xs font-semibold text-ink-500" aria-hidden="true">{row.label}</span>
                  <div className="grid gap-1.5" style={{ gridTemplateColumns: columns }}>
                    {row.seats.map((seat) => {
                      const isSelected = selected.has(seat.id);
                      const unavailable = seat.status !== 'AVAILABLE' && !isSelected;
                      const label = `${row.label}${seat.number}`;
                      return (
                        <button key={seat.id} type="button" style={{ gridColumn: seat.col }}
                                className={`${seatClass(seat.status, isSelected)} ${flashing?.has(seat.id) ? 'animate-pulse-ring' : ''}`}
                                disabled={unavailable || !map.bookable}
                                aria-pressed={isSelected}
                                aria-label={`Seat ${label}, ${CATEGORY_LABEL[row.category]}, ${isSelected ? 'selected' : seat.status.toLowerCase()}`}
                                onClick={() => onToggle(seat, row.label)}>
                          {seat.number}
                        </button>
                      );
                    })}
                  </div>
                  <span className="w-5 text-xs font-semibold text-ink-500" aria-hidden="true">{row.label}</span>
                </div>
              ))}
            </div>
          </div>
        ))}
        <div className="pt-4" aria-hidden="true">
          <div className="mx-auto h-2 w-4/5 rounded-[100%] bg-gradient-to-r from-transparent via-sun-300 to-transparent shadow-[0_-12px_40px_6px_rgba(255,195,92,0.35)]" />
          <p className="mt-3 text-center text-[10px] font-bold uppercase tracking-[0.4em] text-ink-400">Screen this way</p>
        </div>
      </div>
    </div>
  );
});

export function SeatLegend() {
  const item = (cls: string, text: string) => (
    <span className="flex items-center gap-2"><span className={cls.replace('h-7 w-7', 'h-4 w-4').replace('sm:h-8 sm:w-8', '')} aria-hidden="true" />{text}</span>
  );
  return (
    <div className="flex flex-wrap justify-center gap-x-5 gap-y-2 text-xs text-ink-300">
      {item(seatClass('AVAILABLE', false), 'Available')}
      {item(seatClass('AVAILABLE', true), 'Selected')}
      {item(seatClass('HELD', false), 'On hold')}
      {item(seatClass('BOOKED', false), 'Booked')}
    </div>
  );
}
