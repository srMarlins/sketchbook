import clsx from 'clsx';
import type { ReactNode } from 'react';

export type NotebookKind = 'lined' | 'kraft' | 'manila';

export interface NotebookPageProps {
  kind?: NotebookKind;
  header?: ReactNode;
  children?: ReactNode;
  className?: string;
}

const KIND_CLS: Record<NotebookKind, string> = {
  lined: 'bg-surface-page',
  kraft: 'bg-surface-kraft',
  manila: 'bg-[color-mix(in_srgb,var(--surface-page)_85%,#d8b975)]',
};

export function NotebookPage({ kind = 'lined', header, children, className }: NotebookPageProps) {
  return (
    <div
      className={clsx(
        'relative shadow-page rounded-sm min-h-[80vh]',
        KIND_CLS[kind],
        className,
      )}
      style={{
        backgroundImage:
          kind === 'lined'
            ? 'repeating-linear-gradient(transparent 0, transparent 31px, var(--rule-line) 31px, var(--rule-line) 32px), url("/textures/paper-grain.webp")'
            : 'url("/textures/paper-grain.webp")',
        backgroundSize: 'auto, auto',
      }}
    >
      <span
        aria-hidden
        className="absolute inset-y-0 left-0 w-6"
        style={{
          background:
            'repeating-linear-gradient(to bottom, transparent 0 14px, rgba(40,28,18,0.18) 14px 18px)',
        }}
      />
      <span
        aria-hidden
        className="absolute inset-y-0 left-7 w-px bg-accent-action/40"
      />
      {header ? (
        <div className="sticky top-0 z-strip pl-12 pr-6 py-3 backdrop-blur-sm bg-[color-mix(in_srgb,var(--surface-page)_85%,transparent)]">
          {header}
        </div>
      ) : null}
      <div className="pl-12 pr-6 py-4">{children}</div>
    </div>
  );
}
