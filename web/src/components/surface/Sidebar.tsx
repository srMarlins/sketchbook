import { useEffect, useRef, useState } from 'react';
import { NavStrip } from '../data/NavStrip';
import type { SpriteName } from '../primitives/sprite-names';

export interface SidebarItem {
  id: string;
  label: string;
  icon?: SpriteName;
  badge?: string | number | null;
}

export interface SidebarProps {
  items: SidebarItem[];
  activeId?: string;
  onActivate?: (id: string) => void;
  className?: string;
}

export function Sidebar({ items, activeId, onActivate, className }: SidebarProps) {
  const [focusIdx, setFocusIdx] = useState<number>(() => {
    const idx = items.findIndex((i) => i.id === activeId);
    return idx >= 0 ? idx : 0;
  });
  const listRef = useRef<HTMLUListElement>(null);

  useEffect(() => {
    const buttons = listRef.current?.querySelectorAll<HTMLButtonElement>('button');
    buttons?.[focusIdx]?.focus({ preventScroll: true });
  }, [focusIdx]);

  return (
    <nav aria-label="Notebook sections" className={className}>
      <ul ref={listRef} className="space-y-2">
        {items.map((item, i) => {
          const props = {
            label: item.label,
            active: item.id === activeId,
            tabIndex: i === focusIdx ? 0 : -1,
            onClick: () => {
              setFocusIdx(i);
              onActivate?.(item.id);
            },
            onKeyDown: (e: React.KeyboardEvent<HTMLButtonElement>) => {
              if (e.key === 'ArrowDown') {
                e.preventDefault();
                setFocusIdx((p) => (p + 1) % items.length);
              } else if (e.key === 'ArrowUp') {
                e.preventDefault();
                setFocusIdx((p) => (p - 1 + items.length) % items.length);
              } else if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                onActivate?.(item.id);
              }
            },
            ...(item.icon !== undefined ? { icon: item.icon } : {}),
            ...(item.badge !== undefined ? { badge: item.badge } : {}),
          };
          return (
            <li key={item.id}>
              <NavStrip {...props} />
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
