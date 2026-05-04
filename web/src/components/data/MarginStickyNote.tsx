import clsx from 'clsx';
import { useMemo } from 'react';
import { mulberry32, seedFromString } from '../../lib/seed';
import { Sprite } from '../primitives/Sprite';

export interface MarginStickyNoteProps {
  id: string;
  text: string;
  onOpenSuggestion?: (id: string) => void;
  className?: string;
}

const PIN_COLORS = [
  'var(--pin-yellow)',
  'var(--pin-blue)',
  'var(--pin-orange)',
  'var(--pin-purple)',
  'var(--pin-green)',
] as const;

export function MarginStickyNote({ id, text, onOpenSuggestion, className }: MarginStickyNoteProps) {
  const { rotation, color } = useMemo(() => {
    const r = mulberry32(seedFromString(`note:${id}`));
    return {
      rotation: (r() * 10 - 5).toFixed(2),
      color: PIN_COLORS[Math.floor(r() * PIN_COLORS.length)] ?? PIN_COLORS[0]!,
    };
  }, [id]);

  return (
    <button
      type="button"
      onClick={() => onOpenSuggestion?.(id)}
      aria-label="Open suggestion"
      className={clsx(
        'group relative inline-block w-44 p-3 pt-5 text-left',
        'bg-pin-yellow/85 shadow-lift',
        'transition-transform duration-fast ease-paper hover:-translate-y-[1px]',
        className,
      )}
      style={{ transform: `rotate(${rotation}deg)`, backgroundColor: color }}
    >
      <span
        aria-hidden
        className="absolute -top-2 left-1/2 -translate-x-1/2 inline-flex"
        style={{ filter: 'drop-shadow(0 1px 1px rgba(0,0,0,0.25))' }}
      >
        <Sprite name="paperclip" size={24} />
      </span>
      <span className="block font-display text-base text-ink-primary leading-snug max-w-full">
        {text}
      </span>
    </button>
  );
}
