import clsx from 'clsx';
import type { ReactNode } from 'react';

export type NotebookKind = 'plain' | 'ruled' | 'tinted';

export interface NotebookPageProps {
  kind?: NotebookKind;
  /** Optional pastel tint for `kind="tinted"`. */
  tint?: 'blue' | 'rose' | 'sage' | 'cream';
  header?: ReactNode;
  children?: ReactNode;
  className?: string;
}

/**
 * The main content surface — a paper card with a soft shadow. Optionally
 * shows a faint blue ruling, or a pastel tint. No torn edges, no holes.
 */
export function NotebookPage({
  kind = 'plain',
  tint = 'cream',
  header,
  children,
  className,
}: NotebookPageProps) {
  const tintCls =
    kind === 'tinted'
      ? {
          blue: 'bg-paper-tint-blue',
          rose: 'bg-paper-tint-rose',
          sage: 'bg-paper-tint-sage',
          cream: 'bg-paper-tint-cream',
        }[tint]
      : 'bg-surface-card';

  return (
    <div
      className={clsx(
        'relative rounded-card shadow-card overflow-hidden',
        tintCls,
        className,
      )}
      style={
        kind === 'ruled'
          ? {
              backgroundImage:
                'repeating-linear-gradient(to bottom, transparent 0 27px, var(--rule-line) 27px 28px)',
              backgroundSize: '100% auto',
            }
          : undefined
      }
    >
      {header ? (
        <div className="sticky top-0 z-10 px-6 py-3 bg-surface-card/90 backdrop-blur-sm border-b border-rule-line">
          {header}
        </div>
      ) : null}
      <div className="px-6 py-5">{children}</div>
    </div>
  );
}
