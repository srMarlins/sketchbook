import { renderHook, act } from '@testing-library/react';
import { beforeEach, describe, expect, test } from 'vitest';
import { useTheme } from './useTheme';
import { THEME_STORAGE_KEY } from '../theme';

describe('useTheme', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
  });

  test('starts in light theme by default', () => {
    const { result } = renderHook(() => useTheme());
    expect(result.current.theme).toBe('light');
    expect(document.documentElement.dataset.theme).toBe('light');
  });

  test('setTheme switches and persists to localStorage', () => {
    const { result } = renderHook(() => useTheme());
    act(() => {
      result.current.setTheme('dark');
    });
    expect(result.current.theme).toBe('dark');
    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark');
  });

  test('toggle flips between light and dark', () => {
    const { result } = renderHook(() => useTheme());
    act(() => {
      result.current.setTheme('light');
    });
    act(() => {
      result.current.toggle();
    });
    expect(result.current.theme).toBe('dark');
    act(() => {
      result.current.toggle();
    });
    expect(result.current.theme).toBe('light');
  });
});
