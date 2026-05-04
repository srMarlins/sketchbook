import { describe, expect, test } from 'vitest';
import {
  ALS_HEX,
  TEXT_ON_ALS,
  contrastRatio,
  inkHexForStrip,
} from './contrast-table';

describe('Ableton-14 contrast table', () => {
  for (let i = 1; i <= 14; i++) {
    test(`als-${i} × text-on choice meets WCAG AA 4.5:1`, () => {
      const strip = ALS_HEX[i]!;
      const ink = inkHexForStrip(i);
      const ratio = contrastRatio(strip, ink);
      expect(ratio, `als-${i} (${strip}) vs ${TEXT_ON_ALS[i]} ink (${ink})`).toBeGreaterThanOrEqual(4.5);
    });
  }
});
