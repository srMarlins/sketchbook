import clsx from 'clsx';
import type { Shelf } from '../../lib/types';

export type HighlightShelfId =
  | 'currently-working'
  | 'forgotten-gems'
  | 'almost-done'
  | 'has-potential'
  | 'untriaged'
  | 'broken';

export interface HighlightsStripProps {
  shelves: Shelf[];
  onSelect: (shelfId: HighlightShelfId) => void;
  className?: string;
}

interface CategoryDef {
  id: HighlightShelfId;
  fallbackTitle: string;
  /** A small color dot, or null for no dot. Uses CSS custom properties from the als palette. */
  dot: string | null;
  /** Optional tint background class (subtle). */
  tint: string;
}

// Display order is fixed.
const CATEGORIES: CategoryDef[] = [
  {
    id: 'currently-working',
    fallbackTitle: 'Currently working on',
    dot: 'var(--als-11)', // blue
    tint: 'bg-paper-tint-blue',
  },
  {
    id: 'forgotten-gems',
    fallbackTitle: 'Forgotten gems',
    dot: null,
    tint: 'bg-paper-tint-cream',
  },
  {
    id: 'almost-done',
    fallbackTitle: 'Almost done',
    dot: 'var(--als-3)', // orange
    tint: 'bg-paper-tint-cream',
  },
  {
    id: 'has-potential',
    fallbackTitle: 'Has potential',
    dot: 'var(--als-13)', // purple
    tint: 'bg-paper-tint-rose',
  },
  {
    id: 'untriaged',
    fallbackTitle: 'Untriaged',
    dot: null,
    tint: 'bg-paper-tint-cream',
  },
  {
    id: 'broken',
    fallbackTitle: 'Broken',
    dot: 'var(--als-1)', // red — small breadcrumb, not a flashing alarm
    tint: 'bg-paper-tint-rose',
  },
];

/**
 * A horizontal row of slim category chips that surface the home shelves.
 * Sits above the main content; each chip shows a count and selects the shelf.
 * Empty categories stay in the row (dim) — don't hide.
 */
export function HighlightsStrip({ shelves, onSelect, className }: HighlightsStripProps) {
  const byId = new Map<string, Shelf>();
  for (const s of shelves) byId.set(s.id, s);

  return (
    <div
      role="toolbar"
      aria-label="Project highlights"
      className={clsx('flex flex-wrap items-center gap-2', className)}
    >
      {CATEGORIES.map((cat) => {
        const shelf = byId.get(cat.id);
        const count = shelf?.projects.length ?? 0;
        const title = shelf?.title ?? cat.fallbackTitle;
        const empty = count === 0;
        return (
          <button
            key={cat.id}
            type="button"
            data-testid={`highlight-chip-${cat.id}`}
            data-empty={empty || undefined}
            onClick={() => onSelect(cat.id)}
            className={clsx(
              'group inline-flex items-center gap-2 px-2.5 py-1 rounded-chip',
              'border border-rule-line',
              cat.tint,
              'font-mono text-[11px] uppercase tracking-wider',
              'text-ink-secondary',
              'transition-shadow duration-fast ease-paper',
              'hover:shadow-pin focus-visible:shadow-pin',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent',
              'motion-reduce:transition-none',
              empty && 'opacity-50 text-ink-muted',
            )}
          >
            {cat.dot ? (
              <span
                aria-hidden
                className="inline-block w-2 h-2 rounded-full shrink-0"
                style={{ backgroundColor: cat.dot }}
              />
            ) : (
              <span aria-hidden className="inline-block w-2 h-2 shrink-0" />
            )}
            <span className="truncate">{title}</span>
            <span className="text-ink-muted tabular-nums normal-case">· {count}</span>
          </button>
        );
      })}
    </div>
  );
}
