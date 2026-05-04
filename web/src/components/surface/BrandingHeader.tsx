import clsx from 'clsx';

export interface BrandingHeaderProps {
  className?: string;
}

/**
 * Pure-text wordmark. No image asset; the styling does the work.
 */
export function BrandingHeader({ className }: BrandingHeaderProps) {
  return (
    <h1
      aria-label="Audio Catalog"
      className={clsx(
        'flex items-baseline gap-2 select-none',
        className,
      )}
    >
      <span className="text-xl font-semibold tracking-tight text-ink-primary">audio</span>
      <span className="text-xl font-light tracking-tight text-accent">catalog</span>
    </h1>
  );
}
