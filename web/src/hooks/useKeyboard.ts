import { useEffect } from 'react';

export type KeyCombo =
  | 'cmd+k'
  | 'ctrl+k'
  | 'mod+k'
  | 'slash'
  | 'esc'
  | string;

export interface KeyHandler {
  combo: KeyCombo;
  /** Higher priority handlers fire first; if any returns false, propagation stops. */
  priority?: number;
  handler: (event: KeyboardEvent) => boolean | void;
}

interface RegisteredEntry extends KeyHandler {
  id: number;
}

const registry: RegisteredEntry[] = [];
let installed = false;
let nextId = 1;

function matches(combo: KeyCombo, event: KeyboardEvent): boolean {
  switch (combo) {
    case 'esc':
      return event.key === 'Escape';
    case 'slash':
      return event.key === '/' && !event.metaKey && !event.ctrlKey;
    case 'cmd+k':
      return event.metaKey && event.key.toLowerCase() === 'k';
    case 'ctrl+k':
      return event.ctrlKey && event.key.toLowerCase() === 'k';
    case 'mod+k':
      return (event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k';
    default:
      return false;
  }
}

function onKeyDown(event: KeyboardEvent) {
  // Snapshot in priority order: highest first; ties keep registration order.
  const ordered = [...registry].sort((a, b) => (b.priority ?? 0) - (a.priority ?? 0));
  for (const entry of ordered) {
    if (!matches(entry.combo, event)) continue;
    const result = entry.handler(event);
    if (result === false) return;
  }
}

function ensureInstalled() {
  if (installed || typeof window === 'undefined') return;
  window.addEventListener('keydown', onKeyDown);
  installed = true;
}

export function useKeyboard(handler: KeyHandler) {
  useEffect(() => {
    ensureInstalled();
    const id = nextId++;
    registry.push({ ...handler, id });
    return () => {
      const i = registry.findIndex((entry) => entry.id === id);
      if (i >= 0) registry.splice(i, 1);
    };
  }, [handler.combo, handler.priority, handler.handler]);
}

/** Test-only helper: clears registered handlers between tests. */
export function __resetKeyboardRegistry() {
  registry.length = 0;
}
