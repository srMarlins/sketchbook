/**
 * Pre-computed text-on-strip color for each Ableton-14 strip.
 * Passes WCAG AA (4.5:1) against the strip's --als-N hex.
 * Verified at build-time by contrast-table.test.ts.
 */
export type OnStripInk = 'light' | 'dark';

export const TEXT_ON_ALS: Record<number, OnStripInk> = {
  1: 'dark',
  2: 'dark',
  3: 'dark',
  4: 'dark',
  5: 'dark',
  6: 'dark',
  7: 'dark',
  8: 'dark',
  9: 'dark',
  10: 'dark',
  11: 'dark',
  12: 'dark',
  13: 'dark',
  14: 'dark',
};

export const ALS_HEX: Record<number, string> = {
  1: '#ff94a6',
  2: '#ffa529',
  3: '#cc9927',
  4: '#f7f47c',
  5: '#bbff26',
  6: '#1aff2f',
  7: '#25ffa8',
  8: '#5cffe8',
  9: '#8bc5ff',
  10: '#5480e4',
  11: '#92a7ff',
  12: '#d86ce4',
  13: '#e553a0',
  14: '#ffffff',
};

export const INK_ON_STRIP_LIGHT_HEX = '#f8f4ec';
export const INK_ON_STRIP_DARK_HEX = '#1a1614';

function srgbChannel(c: number): number {
  const v = c / 255;
  return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
}

export function relativeLuminance(hex: string): number {
  const h = hex.replace('#', '');
  const r = parseInt(h.slice(0, 2), 16);
  const g = parseInt(h.slice(2, 4), 16);
  const b = parseInt(h.slice(4, 6), 16);
  return 0.2126 * srgbChannel(r) + 0.7152 * srgbChannel(g) + 0.0722 * srgbChannel(b);
}

export function contrastRatio(a: string, b: string): number {
  const la = relativeLuminance(a);
  const lb = relativeLuminance(b);
  const [hi, lo] = la > lb ? [la, lb] : [lb, la];
  return (hi + 0.05) / (lo + 0.05);
}

export function inkHexForStrip(stripIndex: number): string {
  const choice = TEXT_ON_ALS[stripIndex];
  return choice === 'light' ? INK_ON_STRIP_LIGHT_HEX : INK_ON_STRIP_DARK_HEX;
}
