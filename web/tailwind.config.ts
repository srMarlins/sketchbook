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
        // surfaces
        'paper-base': 'var(--paper-base)',
        'paper-raised': 'var(--paper-raised)',
        'paper-sunken': 'var(--paper-sunken)',
        'paper-tint-blue': 'var(--paper-tint-blue)',
        'paper-tint-rose': 'var(--paper-tint-rose)',
        'paper-tint-sage': 'var(--paper-tint-sage)',
        'paper-tint-cream': 'var(--paper-tint-cream)',
        'surface-page': 'var(--surface-page)',
        'surface-card': 'var(--surface-card)',
        'surface-sunken': 'var(--surface-sunken)',
        'surface-overlay': 'var(--surface-overlay)',
        // ink
        'ink-primary': 'var(--ink-primary)',
        'ink-secondary': 'var(--ink-secondary)',
        'ink-muted': 'var(--ink-muted)',
        'ink-faint': 'var(--ink-faint)',
        'ink-on-fill': 'var(--ink-on-fill)',
        // rules
        'rule-line': 'var(--rule-line)',
        'rule-line-strong': 'var(--rule-line-strong)',
        'rule-margin': 'var(--rule-margin)',
        'rule-blue': 'var(--rule-blue)',
        // accents
        accent: 'var(--accent)',
        'accent-soft': 'var(--accent-soft)',
        'accent-muted': 'var(--accent-muted)',
        'accent-positive': 'var(--accent-positive)',
        'accent-warning': 'var(--accent-warning)',
        'accent-danger': 'var(--accent-danger)',
        ...alsColors,
        // legacy aliases — to be removed once the components/* sweep finishes
        'surface-strip': 'var(--surface-card)',
        'surface-corkboard': 'var(--surface-card)',
        'surface-kraft': 'var(--paper-tint-cream)',
        'surface-panel': 'var(--surface-card)',
        'surface-desk': 'var(--surface-page)',
        'ink-on-strip-light': 'var(--ink-primary)',
        'ink-on-strip-dark': 'var(--ink-primary)',
        'accent-action': 'var(--accent)',
        'accent-secondary': 'var(--accent-muted)',
        'pin-green': 'var(--accent-positive)',
        'pin-blue': 'var(--als-10)',
        'pin-orange': 'var(--accent-warning)',
        'pin-purple': 'var(--als-12)',
        'pin-red': 'var(--accent-danger)',
        'pin-yellow': 'var(--accent-warning)',
      },
      fontFamily: {
        display: 'var(--font-display)',
        mono: 'var(--font-mono)',
        sans: 'var(--font-sans)',
      },
      boxShadow: {
        card: 'var(--shadow-card)',
        lift: 'var(--shadow-lift)',
        deep: 'var(--shadow-deep)',
        // legacy aliases
        page: 'var(--shadow-card)',
        pin: 'var(--shadow-card)',
      },
      borderRadius: {
        card: 'var(--radius-card)',
        input: 'var(--radius-input)',
        chip: 'var(--radius-chip)',
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
        page: '0',
        strip: '10',
        overlay: '20',
        panel: '30',
        toast: '40',
      },
    },
  },
  plugins: [],
} satisfies Config;
