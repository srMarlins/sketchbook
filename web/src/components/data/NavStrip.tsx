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
        'group relative flex w-full items-center gap-2 px-3 py-1.5 rounded-input',
        'text-sm font-medium text-left transition-colors duration-fast',
        active
          ? 'bg-surface-card text-ink-primary shadow-card'
          : 'text-ink-secondary hover:bg-surface-card hover:text-ink-primary',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent',
        className,
      )}
      {...rest}
    >
      {icon ? (
        <Sprite
          name={icon}
          size={16}
          className={clsx('shrink-0', active ? 'text-accent' : 'text-ink-muted')}
        />
      ) : null}
      <span className="flex-1 truncate">{label}</span>
      {badge != null && badge !== '' && badge !== 0 ? (
        <span className="font-mono text-[10px] px-1.5 py-0.5 rounded-chip bg-accent text-ink-on-fill leading-none">
          {badge}
        </span>
      ) : null}
    </button>
  );
});
