import clsx from 'clsx';
import type { ReactNode } from 'react';

export interface ShelfProps {
  children?: ReactNode;
  className?: string;
}

/**
 * Shelf: a wood-grain horizontal band that holds Notebook spines.
 * The shadow line at the bottom suggests a wooden ledge under the books.
 */
export function Shelf({ children, className }: ShelfProps) {
  return (
    <section
      aria-label="Shelf"
      className={clsx('relative flex flex-wrap items-end gap-4 p-6 rounded-sm', className)}
      style={{
        backgroundImage:
          'linear-gradient(var(--tint-overlay), var(--tint-overlay)), url("/textures/wood-grain.webp")',
        backgroundRepeat: 'repeat',
      }}
    >
      <span
        aria-hidden
        className="absolute left-0 right-0 bottom-0 h-2"
        style={{ background: 'rgba(0,0,0,0.25)' }}
      />
      {children}
    </section>
  );
}
