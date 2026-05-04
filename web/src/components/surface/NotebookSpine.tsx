import clsx from 'clsx';
import { useMemo } from 'react';
import { mulberry32, seedFromString } from '../../lib/seed';

export type NotebookKind = 'lined' | 'kraft' | 'manila';

export interface NotebookSpineProps {
  id: string;
  title: string;
  kind?: NotebookKind;
  count?: number | null;
  lastUpdated?: string | null;
  onOpen?: (id: string) => void;
}

const KIND_CLS: Record<NotebookKind, string> = {
  lined: 'bg-surface-strip text-ink-primary',
  kraft: 'bg-surface-kraft text-ink-primary',
  manila: 'bg-[color-mix(in_srgb,var(--surface-page)_75%,#d8b975)] text-ink-primary',
};

export function NotebookSpine({
  id,
  title,
  kind = 'lined',
  count,
  lastUpdated,
  onOpen,
}: NotebookSpineProps) {
  const tilt = useMemo(() => {
    const r = mulberry32(seedFromString(`spine:${id}`));
    return (r() * 4 - 2).toFixed(2);
  }, [id]);

  return (
    <button
      type="button"
      onClick={() => onOpen?.(id)}
      className={clsx(
        'group relative inline-flex flex-col items-center justify-end',
        'w-32 h-44 px-2 pb-3 pt-6 rounded-sm shadow-lift',
        'transition-transform duration-fast ease-paper hover:-translate-y-1',
        KIND_CLS[kind],
      )}
      style={{ transform: `rotate(${tilt}deg)` }}
      data-kind={kind}
    >
      <span className="font-display text-lg text-center leading-tight">{title}</span>
      <span className="mt-2 font-mono text-xs text-ink-muted">
        {count != null ? `${count} sketches` : '—'}
      </span>
      {lastUpdated ? (
        <span className="mt-1 font-mono text-[10px] text-ink-muted">{lastUpdated}</span>
      ) : null}
    </button>
  );
}
