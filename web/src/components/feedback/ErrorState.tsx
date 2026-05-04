import { Sprite } from '../primitives/Sprite';
import { Button } from '../inputs/Button';

export interface ErrorStateProps {
  title?: string;
  body?: string;
  retryLabel?: string;
  onRetry?: () => void;
}

export function ErrorState({
  title = 'something tore',
  body,
  retryLabel = 'try again',
  onRetry,
}: ErrorStateProps) {
  return (
    <div
      role="alert"
      className="flex flex-col items-center justify-center text-center gap-3 py-10 px-6 text-ink-muted"
    >
      <Sprite name="rainstorm-cloud" size={64} />
      <p className="font-display text-2xl text-accent-action">{title}</p>
      {body ? <p className="font-sans text-sm max-w-md">{body}</p> : null}
      {onRetry ? (
        <Button variant="secondary" onClick={onRetry}>
          {retryLabel}
        </Button>
      ) : null}
    </div>
  );
}
