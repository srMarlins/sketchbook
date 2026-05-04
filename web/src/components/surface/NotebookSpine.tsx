import clsx from 'clsx';

export type NotebookKind = 'plain' | 'tinted-blue' | 'tinted-rose' | 'tinted-sage' | 'tinted-cream';

export interface NotebookSpineProps {
  id: string;
  title: string;
  kind?: NotebookKind;
  count?: number | null;
  lastUpdated?: string | null;
  onOpen?: (id: string) => void;
}

const KIND_BG: Record<NotebookKind, string> = {
  plain: 'bg-surface-card',
  'tinted-blue': 'bg-paper-tint-blue',
  'tinted-rose': 'bg-paper-tint-rose',
  'tinted-sage': 'bg-paper-tint-sage',
  'tinted-cream': 'bg-paper-tint-cream',
};

/**
 * A clickable card representing a notebook (a group of projects). Replaces
 * the old tilted "spine" book. Stationery-style: clean rectangle, soft shadow,
 * a subtle red margin line on the left to suggest "ruled paper".
 */
export function NotebookSpine({
  id,
  title,
  kind = 'plain',
  count,
  lastUpdated,
  onOpen,
}: NotebookSpineProps) {
  return (
    <button
      type="button"
      onClick={() => onOpen?.(id)}
      className={clsx(
        'group relative text-left',
        'w-48 h-32 px-4 py-3 rounded-card shadow-card',
        'transition-all duration-fast ease-paper hover:-translate-y-0.5 hover:shadow-lift',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent',
        KIND_BG[kind],
      )}
    >
      <span aria-hidden className="absolute left-3 top-2 bottom-2 w-px bg-rule-margin/40" />
      <span className="block pl-4 text-base font-semibold text-ink-primary leading-snug line-clamp-2">
        {title}
      </span>
      <span className="absolute left-7 right-4 bottom-3 flex items-baseline justify-between font-mono text-[11px] text-ink-muted">
        <span>{count != null ? `${count} project${count === 1 ? '' : 's'}` : '—'}</span>
        {lastUpdated ? <span>{lastUpdated}</span> : null}
      </span>
    </button>
  );
}
