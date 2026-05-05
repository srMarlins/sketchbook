import { useCallback, useReducer } from 'react';
import { useIndexerEvents, type IndexerEvent } from '../hooks/useIndexerEvents';
import { useProjectsCount } from './queries';

/**
 * First-launch splash: covers the home shell when the catalog is empty
 * (cold-DB) and the indexer is mid-scan. Auto-dismisses once the catalog
 * crosses 30 rows so the user lands on real content as soon as the home
 * shell has something useful to show.
 *
 * Visual language matches the rest of the stationery theme — centered card
 * on the page surface, no images, no animation library. The only motion is
 * the progress bar fill.
 *
 * Layered (not redesigned): renders nothing until a `scan_started` event
 * arrives, so a fresh user with no DB still sees the regular empty home
 * shell briefly before the splash overlays.
 */

interface State {
  scanActive: boolean;
  done: number;
  total: number;
  currentPath: string | null;
}

const INITIAL: State = {
  scanActive: false,
  done: 0,
  total: 0,
  currentPath: null,
};

function reducer(state: State, ev: IndexerEvent): State {
  switch (ev.kind) {
    case 'scan_started':
      return {
        scanActive: true,
        done: 0,
        total: ev.to_parse,
        currentPath: null,
      };
    case 'scan_progress':
      return { ...state, done: ev.done, total: ev.total };
    case 'scan_row':
      return { ...state, currentPath: ev.path };
    case 'scan_finished':
      return { ...state, scanActive: false };
    default:
      return state;
  }
}

export function FirstLaunch() {
  const count = useProjectsCount();
  const [state, dispatch] = useReducer(reducer, INITIAL);
  const onEvent = useCallback((ev: IndexerEvent) => dispatch(ev), []);
  useIndexerEvents(onEvent);

  // Catalog has enough rows — hand off to home immediately.
  if (count >= 30) return null;
  // No scan in flight: either pre-scan (don't flash splash before first
  // event lands) or post-`scan_finished` (hand off regardless of count).
  if (!state.scanActive) return null;

  const pct = Math.min(
    100,
    Math.max(0, (state.done / Math.max(state.total, 1)) * 100),
  );

  return (
    <div
      data-testid="first-launch-splash"
      role="dialog"
      aria-label="Indexing your library"
      className="fixed inset-0 z-50 flex items-center justify-center bg-surface-page p-6"
    >
      <div className="w-full max-w-md rounded-card border border-rule-line bg-surface-card p-6 shadow-soft">
        <h1 className="font-display text-lg text-ink-primary">
          Welcome — indexing your library
        </h1>
        <p className="mt-1 text-sm text-ink-secondary">
          We&apos;re cataloging your Ableton projects. This is a one-time scan.
        </p>

        <div className="mt-5">
          <div className="h-2 w-full overflow-hidden rounded-full border border-rule-line bg-surface-page">
            <div
              data-testid="first-launch-progress-fill"
              className="h-full bg-accent-positive transition-[width] duration-300 ease-out"
              style={{ width: `${pct}%` }}
            />
          </div>
          <div className="mt-2 flex items-baseline justify-between font-mono text-[11px] text-ink-muted">
            <span className="tabular-nums">
              {state.done}/{state.total}
            </span>
            <span className="tabular-nums">{Math.round(pct)}%</span>
          </div>
        </div>

        {state.currentPath ? (
          <p
            data-testid="first-launch-current-path"
            title={state.currentPath}
            className="mt-4 truncate font-mono text-[11px] text-ink-muted"
          >
            {state.currentPath}
          </p>
        ) : null}
      </div>
    </div>
  );
}
