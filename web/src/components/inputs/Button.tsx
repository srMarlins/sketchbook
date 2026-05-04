import clsx from 'clsx';
import { forwardRef, type ButtonHTMLAttributes } from 'react';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: 'sm' | 'md';
}

const VARIANT_CLS: Record<ButtonVariant, string> = {
  primary:
    'bg-accent-action text-surface-page hover:translate-y-[-1px] active:translate-y-[1px] shadow-lift',
  secondary:
    'bg-surface-strip text-ink-primary border border-rule-line hover:bg-surface-page',
  ghost: 'bg-transparent text-ink-primary hover:bg-surface-strip/60',
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
        'inline-flex items-center gap-2 font-display rounded-[10px] transition-transform duration-fast ease-paper',
        size === 'sm' ? 'px-3 py-1 text-base' : 'px-4 py-2 text-lg',
        VARIANT_CLS[variant],
        'disabled:opacity-50 disabled:cursor-not-allowed disabled:translate-y-0',
        // rough-edge effect via SVG mask (uses asset from public/raw/torn-edges-4.png as a CSS mask)
        'sketchbook-button',
        className,
      )}
      {...rest}
    >
      {children}
    </button>
  );
});
