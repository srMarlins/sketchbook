import clsx from 'clsx';
import type { ReactNode } from 'react';

export interface DeskProps {
  branding?: ReactNode;
  search?: ReactNode;
  sidebar?: ReactNode;
  children?: ReactNode;
  className?: string;
}

export function Desk({ branding, search, sidebar, children, className }: DeskProps) {
  return (
    <div
      className={clsx(
        'min-h-screen relative flex flex-col bg-surface-desk text-ink-primary z-desk',
        className,
      )}
      style={{
        backgroundImage:
          'linear-gradient(var(--tint-overlay), var(--tint-overlay)), url("/textures/wood-grain.webp")',
        backgroundRepeat: 'repeat',
        backgroundSize: 'auto',
      }}
    >
      <header className="flex items-center gap-4 px-6 py-3 z-strip">
        <div>{branding}</div>
        <div className="flex-1 max-w-xl">{search}</div>
      </header>
      <div className="flex-1 grid gap-4 px-6 pb-8" style={{ gridTemplateColumns: '15rem 1fr' }}>
        <aside className="z-strip pt-4">{sidebar}</aside>
        <main className="z-page min-w-0">{children}</main>
      </div>
    </div>
  );
}
