/**
 * Movies without a poster image get a generated one: a gradient picked from the title, so the
 * same movie always looks the same and the home page stays colourful.
 */
const PALETTES: [string, string, string][] = [
  ['#ff4f62', '#ff8a4c', '#ffc35c'], // marquee
  ['#7c3aed', '#c026d3', '#ff4f62'], // grape to rose
  ['#0ea5e9', '#6366f1', '#a855f7'], // ocean to violet
  ['#14c9a3', '#0ea5e9', '#1e3a8a'], // mint to deep blue
  ['#f59e0b', '#ef4444', '#7f1d1d'], // ember
  ['#ec4899', '#8b5cf6', '#1e1b4b'], // neon night
  ['#22c55e', '#14b8a6', '#0f172a'], // jungle
  ['#fb7185', '#f97316', '#4c1d95'], // sunset
];

export function hashString(text: string): number {
  let h = 2166136261;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}

export function posterPalette(title: string): [string, string, string] {
  return PALETTES[hashString(title) % PALETTES.length];
}

export function posterGradient(title: string): string {
  const [a, b, c] = posterPalette(title);
  const angle = 135 + (hashString(title + '#') % 90);
  return `linear-gradient(${angle}deg, ${a} 0%, ${b} 50%, ${c} 100%)`;
}
