import clsx from 'clsx';
import { useEffect, useRef } from 'react';
import { create } from 'zustand';
import { useKeyboard } from '../../hooks/useKeyboard';
import { Sprite } from '../primitives/Sprite';

interface SearchStore {
  query: string;
  setQuery: (q: string) => void;
  clear: () => void;
}

export const useSearchStore = create<SearchStore>((set) => ({
  query: '',
  setQuery: (query) => set({ query }),
  clear: () => set({ query: '' }),
}));

export interface SearchBarProps {
  placeholder?: string;
  className?: string;
}

export function SearchBar({ placeholder = 'find a sketch…', className }: SearchBarProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const query = useSearchStore((s) => s.query);
  const setQuery = useSearchStore((s) => s.setQuery);
  const clear = useSearchStore((s) => s.clear);

  useKeyboard({
    combo: 'mod+k',
    handler: (e) => {
      e.preventDefault();
      inputRef.current?.focus();
    },
  });
  useKeyboard({
    combo: 'slash',
    handler: (e) => {
      const tag = (e.target as HTMLElement | null)?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA') return;
      e.preventDefault();
      inputRef.current?.focus();
    },
  });
  useKeyboard({
    combo: 'esc',
    priority: 1,
    handler: () => {
      if (document.activeElement === inputRef.current) {
        clear();
        inputRef.current?.blur();
      }
    },
  });

  useEffect(() => {
    return () => clear();
  }, [clear]);

  return (
    <div className={clsx('relative flex items-center', className)}>
      <Sprite
        name="magnifying-glass"
        size={18}
        className="absolute left-3 pointer-events-none text-ink-muted"
      />
      <input
        ref={inputRef}
        role="searchbox"
        aria-label="Search sketches"
        type="text"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder={placeholder}
        className={clsx(
          'w-full font-mono text-base pl-10 pr-4 py-2 rounded-sm',
          'bg-surface-strip text-ink-primary',
          'border border-rule-line placeholder:font-display placeholder:text-ink-muted',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent-action/40 focus-visible:border-accent-action',
        )}
      />
    </div>
  );
}
