import clsx from 'clsx';
import { Sprite } from '../primitives/Sprite';
import type { SpriteName } from '../primitives/sprite-names';

export interface FilterChipProps {
  label: string;
  /** Optional value badge (e.g., "120 BPM"). */
  value?: string;
  /** Optional sprite shown to the left. */
  icon?: SpriteName;
  onDismiss?: () => void;
  className?: string;
}

export function FilterChip({ label, value, icon, onDismiss, className }: FilterChipProps) {
  return (
    <span
      className={clsx(
        'inline-flex items-center gap-1.5 px-2.5 py-1 rounded-[14px]',
        'bg-surface-strip text-ink-primary border border-rule-line',
        'shadow-pin font-display text-base',
        className,
      )}
    >
      {icon ? <Sprite name={icon} size={16} /> : null}
      <span>{label}</span>
      {value ? (
        <span className="font-mono text-sm text-ink-muted ml-1">{value}</span>
      ) : null}
      {onDismiss ? (
        <button
          type="button"
          onClick={onDismiss}
          aria-label={`Remove ${label} filter`}
          className="ml-1 -mr-1 inline-flex h-4 w-4 items-center justify-center text-ink-muted hover:text-ink-primary"
        >
          <Sprite name="x-mark" size={14} />
        </button>
      ) : null}
    </span>
  );
}
