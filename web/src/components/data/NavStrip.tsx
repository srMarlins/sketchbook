import clsx from 'clsx';
import { forwardRef, type ButtonHTMLAttributes } from 'react';
import { Sprite } from '../primitives/Sprite';
import type { SpriteName } from '../primitives/sprite-names';

export interface NavStripProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  label: string;
  icon?: SpriteName;
  active?: boolean;
  badge?: string | number | null;
}

export const NavStrip = forwardRef<HTMLButtonElement, NavStripProps>(function NavStrip(
  { label, icon, active = false, badge, className, ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      type="button"
      aria-current={active ? 'page' : undefined}
      className={clsx(
        'group relative flex w-full items-center gap-2 px-3 py-2 rounded-sm',
        'font-display text-lg text-left transition-transform duration-fast ease-paper',
        'bg-surface-strip text-ink-primary',
        active
          ? 'translate-y-[1px] shadow-pin'
          : 'shadow-lift hover:-translate-y-[1px]',
        className,
      )}
      {...rest}
    >
      {icon ? <Sprite name={icon} size={18} /> : null}
      <span className="flex-1 truncate">{label}</span>
      {badge != null && badge !== '' ? (
        <span className="font-mono text-xs px-1.5 py-0.5 rounded bg-accent-action text-surface-page">
          {badge}
        </span>
      ) : null}
    </button>
  );
});
