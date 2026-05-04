import type { Config } from 'tailwindcss';

const alsColors = Object.fromEntries(
  Array.from({ length: 14 }, (_, i) => [`als-${i + 1}`, `var(--als-${i + 1})`]),
);

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  darkMode: ['selector', '[data-theme="dark"]'],
  theme: {
    extend: {
      colors: {
        'surface-desk': 'var(--surface-desk)',
        'surface-page': 'var(--surface-page)',
        'surface-strip': 'var(--surface-strip-base)',
        'surface-corkboard': 'var(--surface-corkboard)',
        'surface-panel': 'var(--surface-panel)',
        'surface-overlay': 'var(--surface-overlay)',
        'surface-kraft': 'var(--surface-kraft)',
        'ink-primary': 'var(--ink-primary)',
        'ink-secondary': 'var(--ink-secondary)',
        'ink-muted': 'var(--ink-muted)',
        'ink-on-strip-light': 'var(--ink-on-strip-light)',
        'ink-on-strip-dark': 'var(--ink-on-strip-dark)',
        'rule-line': 'var(--rule-line)',
        'accent-action': 'var(--accent-action)',
        'accent-secondary': 'var(--accent-secondary)',
        'pin-green': 'var(--pin-green)',
        'pin-blue': 'var(--pin-blue)',
        'pin-orange': 'var(--pin-orange)',
        'pin-purple': 'var(--pin-purple)',
        'pin-red': 'var(--pin-red)',
        'pin-yellow': 'var(--pin-yellow)',
        ...alsColors,
      },
      fontFamily: {
        display: 'var(--font-display)',
        mono: 'var(--font-mono)',
        sans: 'var(--font-sans)',
      },
      boxShadow: {
        page: 'var(--shadow-page)',
        lift: 'var(--shadow-lift)',
        pin: 'var(--shadow-pin)',
      },
      transitionTimingFunction: {
        paper: 'var(--ease-paper)',
        settle: 'var(--ease-settle)',
      },
      transitionDuration: {
        fast: 'var(--dur-fast)',
        base: 'var(--dur-base)',
        slow: 'var(--dur-slow)',
      },
      zIndex: {
        desk: '0',
        page: '10',
        strip: '20',
        attach: '30',
        panel: '40',
        overlay: '50',
        toast: '60',
      },
    },
  },
  plugins: [],
} satisfies Config;
