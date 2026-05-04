import clsx from 'clsx';
import { Children, type ReactNode } from 'react';

export interface HomeShelfProps {
  title: string;
  description: string;
  seeAllHref?: string;
  seeAllLabel?: string;
  children?: ReactNode;
  className?: string;
}

/**
 * A horizontally-scrolling shelf used on the home page (Netflix-style row).
 * Distinct from `<Shelf>` (which wraps). Each direct child is expected to
 * be a card; we apply `snap-start` to slots so cards land aligned.
 */
export function HomeShelf({
  title,
  description,
  seeAllHref,
  seeAllLabel = 'See all →',
  children,
  className,
}: HomeShelfProps) {
  const items = Children.toArray(children).filter(Boolean);
  const isEmpty = items.length === 0;

  return (
    <section className={clsx('space-y-2', className)} data-testid="home-shelf">
      <header className="flex items-baseline justify-between px-1 gap-3">
        <div className="min-w-0">
          <h2 className="font-display text-xl text-ink-primary leading-tight truncate">
            {title}
          </h2>
          {description ? (
            <p className="text-sm text-ink-muted truncate">{description}</p>
          ) : null}
        </div>
        {seeAllHref ? (
          <a
            href={seeAllHref}
            className={clsx(
              'shrink-0 font-mono text-xs text-ink-secondary hover:text-ink-primary',
              'underline-offset-2 hover:underline',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent rounded-sm px-1',
            )}
          >
            {seeAllLabel}
          </a>
        ) : null}
      </header>

      {isEmpty ? (
        <p className="px-1 py-6 italic text-ink-muted text-sm">Nothing here yet.</p>
      ) : (
        <div
          className={clsx(
            'flex gap-3 overflow-x-auto pb-2 px-1',
            'snap-x snap-mandatory',
            'scrollbar-thin',
          )}
        >
          {items.map((child, i) => (
            <div key={i} className="snap-start shrink-0">
              {child}
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
