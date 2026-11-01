import type { PriceView, SeatCategory, SeatMap, SeatsEvent, SeatStatus, SeatView } from '../api/types';

/** Applies a live "seats" event to the map; returns the same object when nothing changed. */
export function applySeatChanges(map: SeatMap, event: SeatsEvent): SeatMap {
  if (event.showId !== map.showId || event.changes.length === 0) return map;
  const next = new Map<number, SeatStatus>(event.changes.map((c) => [c.seatId, c.status]));
  let changed = false;
  let available = map.available;
  const rows = map.rows.map((row) => {
    let rowChanged = false;
    const seats = row.seats.map((seat) => {
      const status = next.get(seat.id);
      if (!status || status === seat.status) return seat;
      rowChanged = true;
      if (seat.status === 'AVAILABLE') available -= 1;
      if (status === 'AVAILABLE') available += 1;
      return { ...seat, status };
    });
    if (!rowChanged) return row;
    changed = true;
    return { ...row, seats };
  });
  return changed ? { ...map, rows, available } : map;
}

export interface SeatInfo {
  seat: SeatView;
  label: string;
  category: SeatCategory;
}

/** seatId -> its row label ("C7") and category. */
export function indexSeats(map: SeatMap): Map<number, SeatInfo> {
  const index = new Map<number, SeatInfo>();
  for (const row of map.rows) {
    for (const seat of row.seats) {
      index.set(seat.id, { seat, label: `${row.label}${seat.number}`, category: row.category });
    }
  }
  return index;
}

export function priceOf(prices: PriceView[], category: SeatCategory): number {
  return prices.find((p) => p.category === category)?.pricePaise ?? 0;
}

/** Selected seats that someone else has since held or booked. */
export function takenFromSelection(map: SeatMap, selected: Set<number>): number[] {
  const index = indexSeats(map);
  return [...selected].filter((id) => index.get(id)?.seat.status !== 'AVAILABLE');
}

/** Rows grouped into consecutive blocks of the same category, back of the hall first. */
export function categoryBlocks(map: SeatMap): { category: SeatCategory; rows: SeatMap['rows'] }[] {
  // Row A is nearest the screen; customers read the map from the back (premium) to the front.
  const ordered = [...map.rows].reverse();
  const blocks: { category: SeatCategory; rows: SeatMap['rows'] }[] = [];
  for (const row of ordered) {
    const last = blocks[blocks.length - 1];
    if (last && last.category === row.category) last.rows.push(row);
    else blocks.push({ category: row.category, rows: [row] });
  }
  return blocks;
}
