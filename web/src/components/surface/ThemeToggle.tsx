import { useTheme } from '../../hooks/useTheme';
import { Sprite } from '../primitives/Sprite';

/**
 * Small icon-button to flip between light and dark. Uses `moon`/`cloud` from
 * the existing sprite set so we don't add new icon assets.
 */
export function ThemeToggle() {
  const { theme, toggle } = useTheme();
  return (
    <button
      type="button"
      onClick={toggle}
      aria-label={`switch to ${theme === 'light' ? 'dark' : 'light'} theme`}
      className="
        inline-flex items-center justify-center
        w-8 h-8 rounded-input
        text-ink-secondary hover:text-ink-primary
        hover:bg-surface-sunken transition-colors duration-fast
        focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent
      "
    >
      <Sprite name={theme === 'light' ? 'moon' : 'cloud'} size={16} />
    </button>
  );
}
