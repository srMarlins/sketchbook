import clsx from 'clsx';
import { forwardRef, type InputHTMLAttributes } from 'react';

export interface TextInputProps extends InputHTMLAttributes<HTMLInputElement> {
  label?: string;
  hint?: string;
  invalid?: boolean;
}

export const TextInput = forwardRef<HTMLInputElement, TextInputProps>(function TextInput(
  { label, hint, invalid = false, className, id, ...rest },
  ref,
) {
  const autoId = `ti-${Math.random().toString(36).slice(2, 8)}`;
  const inputId = id ?? autoId;
  const describedBy = hint ? `${inputId}-hint` : undefined;
  return (
    <div className="flex flex-col gap-1">
      {label ? (
        <label htmlFor={inputId} className="font-sans text-sm text-ink-secondary">
          {label}
        </label>
      ) : null}
      <input
        ref={ref}
        id={inputId}
        type={rest.type ?? 'text'}
        aria-invalid={invalid || undefined}
        aria-describedby={describedBy}
        className={clsx(
          'font-mono text-base bg-surface-strip text-ink-primary px-3 py-2 rounded-sm',
          'border border-rule-line outline-none',
          'focus-visible:ring-2 focus-visible:ring-accent-action/40 focus-visible:border-accent-action',
          invalid ? 'border-accent-action' : '',
          className,
        )}
        {...rest}
      />
      {hint ? (
        <span id={describedBy} className="font-sans text-xs text-ink-muted">
          {hint}
        </span>
      ) : null}
    </div>
  );
});
