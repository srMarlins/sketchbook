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

// Display order is fixed. Each category gets its own tint + dot pairing so
// chips read distinctly at a glance. Three previously shared paper-tint-cream
// (gems / almost-done / untriaged) and were indistinguishable.
const CATEGORIES: CategoryDef[] = [
  {
    id: 'currently-working',
    fallbackTitle: 'Currently working on',
    dot: 'var(--als-10)', // blue — active
    tint: 'bg-paper-tint-blue',
  },
  {
    id: 'forgotten-gems',
    fallbackTitle: 'Forgotten gems',
    dot: 'var(--accent-warning)', // gold — buried treasure
    tint: 'bg-paper-tint-cream',
  },
  {
    id: 'almost-done',
    fallbackTitle: 'Almost done',
    dot: 'var(--als-2)', // orange
    tint: 'bg-paper-tint-sage',
  },
  {
    id: 'has-potential',
    fallbackTitle: 'Has potential',
    dot: 'var(--als-12)', // magenta
    tint: 'bg-paper-tint-rose',
  },
  {
    id: 'untriaged',
    fallbackTitle: 'Untriaged',
    dot: 'var(--ink-muted)', // neutral gray dot — matches "no decision yet"
    tint: 'bg-surface-card',
  },
  {
    id: 'broken',
    fallbackTitle: 'Broken',
    dot: 'var(--accent-danger)', // red — small breadcrumb, not a flashing alarm
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
              // Stronger border so chips don't fade into the desk surface.
              'border border-rule-line-strong',
              cat.tint,
              'font-mono text-[11px] uppercase tracking-wider',
              'text-ink-secondary',
              'transition-all duration-fast ease-paper',
              'hover:shadow-card hover:-translate-y-px hover:text-ink-primary',
              'focus-visible:shadow-card focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent',
              'motion-reduce:transition-none motion-reduce:hover:translate-y-0',
              empty && 'opacity-55 text-ink-muted hover:translate-y-0 hover:shadow-none',
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
