import clsx from 'clsx';
import { motion, AnimatePresence } from 'framer-motion';
import { Children, useMemo, type ReactElement, type ReactNode } from 'react';
import { mulberry32, seedFromString } from '../../lib/seed';

export interface TornPagePileProps {
  children: ReactNode;
  className?: string;
}

export function TornPagePile({ children, className }: TornPagePileProps) {
  const items = useMemo(
    () => Children.toArray(children).filter((c): c is ReactElement => Boolean(c)),
    [children],
  );

  return (
    <div className={clsx('relative space-y-3', className)}>
      <AnimatePresence initial={false}>
        {items.map((child) => {
          const key = typeof child.key === 'string' || typeof child.key === 'number' ? String(child.key) : '';
          const r = mulberry32(seedFromString(`pile:${key}`));
          const rot = (r() * 4 - 2).toFixed(2);
          const tx = (r() * 8 - 4).toFixed(1);
          return (
            <motion.div
              key={key}
              layout
              initial={{ opacity: 0, y: -8, rotate: 0 }}
              animate={{ opacity: 1, y: 0, rotate: Number(rot), x: Number(tx) }}
              exit={{ opacity: 0, y: 12, rotate: Number(rot) - 4, transition: { duration: 0.15 } }}
              transition={{ duration: 0.2, ease: [0.2, 0.8, 0.2, 1] }}
            >
              {child}
            </motion.div>
          );
        })}
      </AnimatePresence>
    </div>
  );
}
