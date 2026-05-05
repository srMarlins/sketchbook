import clsx from 'clsx';

export type NotebookKind =
  | 'plain'
  | 'tinted-blue'
  | 'tinted-rose'
  | 'tinted-sage'
  | 'tinted-cream'
  | 'aged';

export interface NotebookSpineProps {
  id: string;
  title: string;
  kind?: NotebookKind;
  count?: number | null;
  lastUpdated?: string | null;
  /** Optional small "tab" accent strip color across the top — uses any css color value. */
  tabColor?: string | null;
  onOpen?: (id: string) => void;
}

const KIND_BG: Record<NotebookKind, string> = {
  plain: 'bg-surface-card',
  'tinted-blue': 'bg-paper-tint-blue',
  'tinted-rose': 'bg-paper-tint-rose',
  'tinted-sage': 'bg-paper-tint-sage',
  'tinted-cream': 'bg-paper-tint-cream',
  // 'aged' = a desaturated muted variant for archive — visibly different from
  // the active notebooks, signals "stored away."
  aged: 'bg-paper-sunken',
};

/**
 * A clickable card representing a notebook (a group of projects). Stationery
 * card with a soft shadow, a subtle red ruled-margin line, and an optional
 * colored "tab" strip across the top — the same way physical notebooks have
 * tabbed dividers so different sections feel distinct at a glance.
 */
export function NotebookSpine({
  id,
  title,
  kind = 'plain',
  count,
  lastUpdated,
  tabColor,
  onOpen,
}: NotebookSpineProps) {
  return (
    <button
      type="button"
      onClick={() => onOpen?.(id)}
      className={clsx(
        'group relative overflow-hidden text-left',
        'w-48 h-32 px-4 py-3 rounded-card shadow-card',
        'border border-rule-line-strong',
        'transition-all duration-fast ease-paper hover:-translate-y-0.5 hover:shadow-lift',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent',
        KIND_BG[kind],
      )}
    >
      {tabColor ? (
        <span
          aria-hidden
          className="absolute left-0 right-0 top-0 h-1.5"
          style={{ backgroundColor: tabColor }}
        />
      ) : null}
      <span aria-hidden className="absolute left-3 top-3 bottom-2 w-px bg-rule-margin/40" />
      <span
        className={clsx(
          'block pl-4 text-base font-semibold text-ink-primary leading-snug line-clamp-2',
          tabColor && 'pt-1',
        )}
      >
        {title}
      </span>
      <span className="absolute left-7 right-4 bottom-3 flex items-baseline justify-between font-mono text-[11px] text-ink-muted">
        <span>{count != null ? `${count} project${count === 1 ? '' : 's'}` : '—'}</span>
        {lastUpdated ? <span>{lastUpdated}</span> : null}
      </span>
    </button>
  );
}
