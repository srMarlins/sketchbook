import clsx from 'clsx';
import { forwardRef, type ButtonHTMLAttributes } from 'react';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: 'sm' | 'md';
}

const VARIANT_CLS: Record<ButtonVariant, string> = {
  primary:
    'bg-accent text-ink-on-fill hover:bg-accent/90 shadow-card border border-accent-soft/40',
  secondary:
    'bg-surface-card text-ink-primary border border-rule-line hover:bg-surface-sunken',
  ghost:
    'bg-transparent text-ink-secondary border border-transparent hover:bg-surface-sunken hover:text-ink-primary',
};

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'primary', size = 'md', className, children, type = 'button', ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      className={clsx(
        'inline-flex items-center gap-2 rounded-input font-medium transition-colors duration-fast',
        size === 'sm' ? 'px-2.5 py-1 text-xs' : 'px-3.5 py-1.5 text-sm',
        VARIANT_CLS[variant],
        'disabled:opacity-50 disabled:cursor-not-allowed',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent focus-visible:ring-offset-2 focus-visible:ring-offset-surface-page',
        className,
      )}
      {...rest}
    >
      {children}
    </button>
  );
});
