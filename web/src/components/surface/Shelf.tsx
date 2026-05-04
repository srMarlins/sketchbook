import clsx from 'clsx';
import type { ReactNode } from 'react';

export interface ShelfProps {
  title?: ReactNode;
  children?: ReactNode;
  className?: string;
}

/**
 * Horizontal row container — used to lay out cards in groups. Replaces the
 * old wood-grain shelf. No textures.
 */
export function Shelf({ title, children, className }: ShelfProps) {
  return (
    <section className={clsx('space-y-3', className)}>
      {title ? (
        <header className="px-1 text-sm font-mono text-ink-muted uppercase tracking-wide">
          {title}
        </header>
      ) : null}
      <div className="flex flex-wrap gap-3">{children}</div>
    </section>
  );
}
