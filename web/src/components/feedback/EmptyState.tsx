import { Sprite } from '../primitives/Sprite';
import type { SpriteName } from '../primitives/sprite-names';

export interface EmptyStateProps {
  icon?: SpriteName;
  title: string;
  body?: string;
}

export function EmptyState({ icon = 'cloud', title, body }: EmptyStateProps) {
  return (
    <div className="flex flex-col items-center justify-center text-center py-10 px-6 gap-3 text-ink-muted">
      <Sprite name={icon} size={64} />
      <p className="font-display text-2xl text-ink-primary">{title}</p>
      {body ? <p className="font-sans text-sm max-w-md">{body}</p> : null}
    </div>
  );
}
