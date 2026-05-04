import clsx from 'clsx';
import type { ReactNode } from 'react';
import { ThemeToggle } from './ThemeToggle';

export interface DeskProps {
  branding?: ReactNode;
  search?: ReactNode;
  sidebar?: ReactNode;
  children?: ReactNode;
  className?: string;
}

/**
 * Top-level page chrome: a 2-column grid (sidebar + main) on a paper surface.
 * No wood, no textures — the page background gradient is set on body.
 */
export function Desk({ branding, search, sidebar, children, className }: DeskProps) {
  return (
    <div
      className={clsx(
        'min-h-screen flex flex-col bg-surface-page text-ink-primary',
        className,
      )}
    >
      <header className="flex items-center gap-4 px-6 py-3 border-b border-rule-line">
        <div className="shrink-0">{branding}</div>
        <div className="flex-1 max-w-xl">{search}</div>
        <ThemeToggle />
      </header>
      <div
        className="flex-1 grid gap-6 px-6 py-6 min-h-0"
        style={{ gridTemplateColumns: '14rem minmax(0, 1fr)' }}
      >
        <aside className="min-w-0">{sidebar}</aside>
        <main className="min-w-0">{children}</main>
      </div>
    </div>
  );
}
