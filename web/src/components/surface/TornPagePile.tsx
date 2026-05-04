import clsx from 'clsx';
import { motion, AnimatePresence } from 'framer-motion';
import { Children, useMemo, type ReactElement, type ReactNode } from 'react';

export interface PageStackProps {
  children: ReactNode;
  className?: string;
}

/**
 * Vertical stack with a subtle slide-in/out animation. Replaces the old
 * tilted "torn page pile" — same surface for the proposals queue, but
 * straight pages, no rotation, no torn edges.
 */
export function TornPagePile({ children, className }: PageStackProps) {
  const items = useMemo(
    () => Children.toArray(children).filter((c): c is ReactElement => Boolean(c)),
    [children],
  );

  return (
    <div className={clsx('relative space-y-3', className)}>
      <AnimatePresence initial={false}>
        {items.map((child) => {
          const key =
            typeof child.key === 'string' || typeof child.key === 'number'
              ? String(child.key)
              : '';
          return (
            <motion.div
              key={key}
              layout
              initial={{ opacity: 0, y: -6 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: 6, transition: { duration: 0.15 } }}
              transition={{ duration: 0.18, ease: [0.2, 0.8, 0.2, 1] }}
            >
              {child}
            </motion.div>
          );
        })}
      </AnimatePresence>
    </div>
  );
}
