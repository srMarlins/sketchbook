import { renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, test, vi } from 'vitest';
import { useKeyboard, __resetKeyboardRegistry } from './useKeyboard';

function fire(opts: { key: string; metaKey?: boolean; ctrlKey?: boolean }) {
  window.dispatchEvent(new KeyboardEvent('keydown', { ...opts, bubbles: true }));
}

describe('useKeyboard', () => {
  beforeEach(() => {
    __resetKeyboardRegistry();
  });

  test('Cmd-K fires registered handler', () => {
    const fn = vi.fn();
    renderHook(() => useKeyboard({ combo: 'cmd+k', handler: fn }));
    fire({ key: 'k', metaKey: true });
    expect(fn).toHaveBeenCalledTimes(1);
  });

  test('mod+k matches both meta and ctrl', () => {
    const fn = vi.fn();
    renderHook(() => useKeyboard({ combo: 'mod+k', handler: fn }));
    fire({ key: 'k', metaKey: true });
    fire({ key: 'k', ctrlKey: true });
    expect(fn).toHaveBeenCalledTimes(2);
  });

  test('slash fires for "/"', () => {
    const fn = vi.fn();
    renderHook(() => useKeyboard({ combo: 'slash', handler: fn }));
    fire({ key: '/' });
    expect(fn).toHaveBeenCalledTimes(1);
  });

  test('esc handlers run in priority order; returning false halts', () => {
    const calls: string[] = [];
    renderHook(() =>
      useKeyboard({
        combo: 'esc',
        priority: 1,
        handler: () => {
          calls.push('low');
        },
      }),
    );
    renderHook(() =>
      useKeyboard({
        combo: 'esc',
        priority: 10,
        handler: () => {
          calls.push('high');
          return false;
        },
      }),
    );
    fire({ key: 'Escape' });
    expect(calls).toEqual(['high']);
  });

  test('handler unregisters on unmount', () => {
    const fn = vi.fn();
    const { unmount } = renderHook(() => useKeyboard({ combo: 'esc', handler: fn }));
    unmount();
    fire({ key: 'Escape' });
    expect(fn).not.toHaveBeenCalled();
  });
});
