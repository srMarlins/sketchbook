import { Sprite } from '../primitives/Sprite';

export interface LoadingStateProps {
  label?: string;
}

/**
 * 3-second cap on the spin (tip from design-language §6).
 * Reduced motion: shows the static doodle.
 */
export function LoadingState({ label = 'one sec…' }: LoadingStateProps) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 py-6 text-ink-muted">
      <span
        className="loading-spin"
        style={{
          animationName: 'sketchbook-spin',
          animationDuration: '3s',
          animationIterationCount: 'infinite',
          animationTimingFunction: 'linear',
          display: 'inline-block',
        }}
      >
        <Sprite name="cassette-spool" size={56} />
      </span>
      <p className="font-display text-lg text-ink-primary">{label}</p>
      <style>{`
        @keyframes sketchbook-spin {
          from { transform: rotate(0deg); }
          to { transform: rotate(360deg); }
        }
        @media (prefers-reduced-motion: reduce) {
          .loading-spin { animation: none !important; }
        }
      `}</style>
    </div>
  );
}
