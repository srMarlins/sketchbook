import * as Dialog from '@radix-ui/react-dialog';
import * as Tabs from '@radix-ui/react-tabs';
import { motion, AnimatePresence } from 'framer-motion';
import clsx from 'clsx';
import type { ReactNode } from 'react';

export interface CorkboardTab {
  id: string;
  label: string;
  content: ReactNode;
}

export interface CorkboardPanelProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  tabs: CorkboardTab[];
  defaultTab?: string;
  /** Rendered to the right of the title, before the close button. */
  headerActions?: ReactNode;
}

export function CorkboardPanel({
  open,
  onOpenChange,
  title,
  tabs,
  defaultTab,
  headerActions,
}: CorkboardPanelProps) {
  const initial = defaultTab ?? tabs[0]?.id ?? 'overview';

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <AnimatePresence>
        {open ? (
          <Dialog.Portal forceMount>
            <Dialog.Overlay
              asChild
              className="fixed inset-0 bg-surface-overlay z-overlay"
            >
              <motion.div
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
                transition={{ duration: 0.2, ease: [0.2, 0.8, 0.2, 1] }}
              />
            </Dialog.Overlay>
            <Dialog.Content
              asChild
              className="fixed top-0 right-0 h-screen w-[min(640px,100vw)] z-panel outline-none"
            >
              <motion.aside
                initial={{ x: '100%' }}
                animate={{ x: 0 }}
                exit={{ x: '100%' }}
                transition={{ duration: 0.2, ease: [0.2, 0.8, 0.2, 1] }}
                className="bg-surface-card text-ink-primary shadow-deep h-full flex flex-col border-l border-rule-line-strong"
              >
                <header className="flex items-center gap-3 px-5 py-3 border-b border-rule-line">
                  <Dialog.Title className="flex-1 min-w-0 text-lg font-semibold tracking-tight truncate">
                    {title}
                  </Dialog.Title>
                  {headerActions}
                  <Dialog.Close
                    className="shrink-0 px-2 py-1 rounded-input text-sm border border-rule-line text-ink-secondary hover:bg-surface-sunken hover:text-ink-primary transition-colors"
                    aria-label="Close panel"
                  >
                    close
                  </Dialog.Close>
                </header>

                <Tabs.Root
                  defaultValue={initial}
                  className="flex-1 flex flex-col min-h-0"
                >
                  <Tabs.List
                    aria-label="Detail tabs"
                    className="flex gap-1 px-3 pt-2 border-b border-rule-line bg-surface-sunken"
                  >
                    {tabs.map((t) => (
                      <Tabs.Trigger
                        key={t.id}
                        value={t.id}
                        className={clsx(
                          'text-sm font-medium px-3 py-1.5 rounded-t-input -mb-px',
                          'border border-transparent border-b-0',
                          'transition-colors duration-fast',
                          'text-ink-secondary hover:text-ink-primary',
                          'data-[state=active]:bg-surface-card',
                          'data-[state=active]:text-ink-primary',
                          'data-[state=active]:border-rule-line',
                          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent focus-visible:ring-inset',
                        )}
                      >
                        {t.label}
                      </Tabs.Trigger>
                    ))}
                  </Tabs.List>
                  <div className="flex-1 min-h-0 overflow-y-auto px-5 py-4">
                    {tabs.map((t) => (
                      <Tabs.Content key={t.id} value={t.id} className="outline-none">
                        {t.content}
                      </Tabs.Content>
                    ))}
                  </div>
                </Tabs.Root>
              </motion.aside>
            </Dialog.Content>
          </Dialog.Portal>
        ) : null}
      </AnimatePresence>
    </Dialog.Root>
  );
}
