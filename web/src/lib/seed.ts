/**
 * Deterministic, pure-function seeding helpers.
 * Same input → same output, no global state, no side effects.
 */

const FNV_OFFSET = 0x811c9dc5;
const FNV_PRIME = 0x01000193;

export function seedFromString(input: string): number {
  let hash = FNV_OFFSET;
  for (let i = 0; i < input.length; i++) {
    hash ^= input.charCodeAt(i);
    hash = Math.imul(hash, FNV_PRIME);
  }
  return hash >>> 0;
}

/** mulberry32 — small, fast, well-distributed PRNG. */
export function mulberry32(seed: number): () => number {
  let t = seed >>> 0;
  return () => {
    t = (t + 0x6d2b79f5) >>> 0;
    let r = t;
    r = Math.imul(r ^ (r >>> 15), r | 1);
    r ^= r + Math.imul(r ^ (r >>> 7), r | 61);
    return ((r ^ (r >>> 14)) >>> 0) / 4294967296;
  };
}

export function seedPick<T>(items: readonly T[], seed: number | string): T {
  if (items.length === 0) throw new Error('seedPick: empty array');
  const numeric = typeof seed === 'string' ? seedFromString(seed) : seed >>> 0;
  const rand = mulberry32(numeric);
  const idx = Math.floor(rand() * items.length);
  return items[idx]!;
}

export function seedRange(seed: number | string, min: number, max: number): number {
  const numeric = typeof seed === 'string' ? seedFromString(seed) : seed >>> 0;
  const rand = mulberry32(numeric);
  return min + rand() * (max - min);
}
