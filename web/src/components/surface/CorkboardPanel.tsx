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
}

export function CorkboardPanel({
  open,
  onOpenChange,
  title,
  tabs,
  defaultTab,
}: CorkboardPanelProps) {
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
            <Dialog.Content asChild className="fixed top-0 right-0 h-screen w-[min(560px,100vw)] z-panel outline-none">
              <motion.aside
                initial={{ x: '100%' }}
                animate={{ x: 0 }}
                exit={{ x: '100%' }}
                transition={{ duration: 0.2, ease: [0.2, 0.8, 0.2, 1] }}
                className="bg-surface-corkboard text-ink-primary shadow-page h-full flex"
              >
                <Tabs.Root defaultValue={defaultTab ?? tabs[0]?.id ?? 'overview'} className="flex w-full">
                  <Tabs.List
                    aria-label="Corkboard tabs"
                    className="flex flex-col gap-2 py-4 px-2 border-r border-rule-line/30 bg-surface-kraft"
                  >
                    {tabs.map((t) => (
                      <Tabs.Trigger
                        key={t.id}
                        value={t.id}
                        className={clsx(
                          'font-display text-base px-2 py-1 rounded-sm text-left',
                          'data-[state=active]:bg-surface-page data-[state=active]:translate-x-1',
                          'transition-transform duration-fast ease-paper',
                        )}
                      >
                        {t.label}
                      </Tabs.Trigger>
                    ))}
                  </Tabs.List>
                  <div className="flex-1 overflow-y-auto p-4">
                    <header className="mb-4 flex items-center justify-between">
                      <Dialog.Title className="font-display text-2xl">{title}</Dialog.Title>
                      <Dialog.Close
                        className="px-2 py-1 rounded text-sm border border-rule-line hover:bg-surface-page"
                        aria-label="Close panel"
                      >
                        close
                      </Dialog.Close>
                    </header>
                    {tabs.map((t) => (
                      <Tabs.Content key={t.id} value={t.id}>
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
