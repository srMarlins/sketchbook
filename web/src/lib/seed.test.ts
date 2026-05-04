import { describe, expect, test } from 'vitest';
import { seedFromString, seedPick, seedRange, mulberry32 } from './seed';

describe('seedFromString', () => {
  test('is deterministic', () => {
    expect(seedFromString('proj-123')).toBe(seedFromString('proj-123'));
  });

  test('different inputs yield different hashes', () => {
    expect(seedFromString('a')).not.toBe(seedFromString('b'));
  });

  test('returns an unsigned 32-bit integer', () => {
    const h = seedFromString('anything');
    expect(h).toBeGreaterThanOrEqual(0);
    expect(h).toBeLessThan(2 ** 32);
  });
});

describe('seedPick', () => {
  test('returns the same element across calls for the same seed', () => {
    const items = ['a', 'b', 'c', 'd'];
    expect(seedPick(items, 'proj-123')).toBe(seedPick(items, 'proj-123'));
  });

  test('returns an element from the input', () => {
    const items = ['a', 'b', 'c'] as const;
    expect(items).toContain(seedPick(items, 'whatever'));
  });

  test('throws on empty array', () => {
    expect(() => seedPick([], 'x')).toThrow();
  });

  test('different seeds spread across all elements', () => {
    const items = ['a', 'b', 'c', 'd'];
    const seen = new Set<string>();
    for (let i = 0; i < 200; i++) seen.add(seedPick(items, `seed-${i}`));
    expect(seen.size).toBe(items.length);
  });
});

describe('seedRange + mulberry32', () => {
  test('seedRange stays within bounds', () => {
    for (let i = 0; i < 50; i++) {
      const v = seedRange(`s-${i}`, -3, 3);
      expect(v).toBeGreaterThanOrEqual(-3);
      expect(v).toBeLessThan(3);
    }
  });

  test('mulberry32 emits values in [0,1)', () => {
    const r = mulberry32(42);
    for (let i = 0; i < 20; i++) {
      const v = r();
      expect(v).toBeGreaterThanOrEqual(0);
      expect(v).toBeLessThan(1);
    }
  });
});
