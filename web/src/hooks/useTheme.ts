import { useEffect } from 'react';
import { create } from 'zustand';
import { THEME_STORAGE_KEY, type Theme } from '../theme';

interface ThemeStore {
  theme: Theme;
  setTheme: (theme: Theme) => void;
  toggle: () => void;
}

function readInitial(): Theme {
  if (typeof window === 'undefined') return 'light';
  const saved = window.localStorage.getItem(THEME_STORAGE_KEY);
  if (saved === 'dark' || saved === 'light') return saved;
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

function applyTheme(theme: Theme) {
  if (typeof document === 'undefined') return;
  document.documentElement.dataset.theme = theme;
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, theme);
  } catch {
    /* private mode etc. */
  }
}

const useThemeStore = create<ThemeStore>((set, get) => {
  const initial = readInitial();
  applyTheme(initial);
  return {
    theme: initial,
    setTheme: (theme) => {
      applyTheme(theme);
      set({ theme });
    },
    toggle: () => {
      const next: Theme = get().theme === 'light' ? 'dark' : 'light';
      applyTheme(next);
      set({ theme: next });
    },
  };
});

export function useTheme() {
  const theme = useThemeStore((s) => s.theme);
  const setTheme = useThemeStore((s) => s.setTheme);
  const toggle = useThemeStore((s) => s.toggle);

  useEffect(() => {
    applyTheme(theme);
  }, [theme]);

  return { theme, setTheme, toggle };
}
