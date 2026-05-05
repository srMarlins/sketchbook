import { useCallback, useReducer } from 'react';
import clsx from 'clsx';
import { useIndexerEvents, type IndexerEvent } from '../hooks/useIndexerEvents';
import { useFindings } from './queries';

/**
 * Small chip in the page header that surfaces live indexer state from
 * the SSE bus: idle (watching), scanning, catching up (backfill), and
 * watcher-fallback warnings. Plus a tiny findings badge that lights up
 * when detectors have flagged rows that need attention.
 *
 * Layered onto the existing header — visual language matches FilterChip
 * (rounded, rule-line border, surface-card fill).
 */

type ProgressState =
  | { kind: 'idle' }
  | { kind: 'scanning'; done: number; total: number }
  | { kind: 'backfilling'; name: string; done: number; total: number };

type WatcherMode = 'watching' | 'polling' | 'off';

interface StatusState {
  scan: ProgressState;
  backfill: ProgressState;
  watcherMode: WatcherMode;
  watcherReason?: string;
}

const INITIAL: StatusState = {
  scan: { kind: 'idle' },
  backfill: { kind: 'idle' },
  watcherMode: 'watching',
};

function reducer(state: StatusState, ev: IndexerEvent): StatusState {
  switch (ev.kind) {
    case 'scan_started':
      return {
        ...state,
        scan: { kind: 'scanning', done: 0, total: ev.to_parse },
      };
    case 'scan_progress':
      return {
        ...state,
        scan: { kind: 'scanning', done: ev.done, total: ev.total },
      };
    case 'scan_finished':
      return { ...state, scan: { kind: 'idle' } };
    case 'backfill_started':
      return {
        ...state,
        backfill: {
          kind: 'backfilling',
          name: ev.name,
          done: 0,
          total: ev.total,
        },
      };
    case 'backfill_progress':
      return {
        ...state,
        backfill: {
          kind: 'backfilling',
          name: ev.name,
          done: ev.done,
          total: ev.total,
        },
      };
    case 'backfill_finished':
      return { ...state, backfill: { kind: 'idle' } };
    case 'watcher_status': {
      const next: StatusState = { ...state, watcherMode: ev.mode };
      if (ev.reason !== undefined) next.watcherReason = ev.reason;
      return next;
    }
    default:
      return state;
  }
}

type ChipKind =
  | { kind: 'idle' }
  | { kind: 'scanning'; done: number; total: number }
  | { kind: 'backfilling'; name: string; done: number; total: number }
  | { kind: 'watcher_warning'; reason?: string };

function deriveChip(state: StatusState): ChipKind {
  if (state.backfill.kind === 'backfilling') {
    return {
      kind: 'backfilling',
      name: state.backfill.name,
      done: state.backfill.done,
      total: state.backfill.total,
    };
  }
  if (state.scan.kind === 'scanning') {
    return {
      kind: 'scanning',
      done: state.scan.done,
      total: state.scan.total,
    };
  }
  if (state.watcherMode === 'polling') {
    const out: ChipKind = { kind: 'watcher_warning' };
    if (state.watcherReason !== undefined) out.reason = state.watcherReason;
    return out;
  }
  return { kind: 'idle' };
}

function Spinner() {
  return (
    <span
      data-testid="indexer-status-spinner"
      aria-hidden="true"
      className="inline-block h-3 w-3 rounded-full border border-rule-line border-t-ink-secondary animate-spin"
    />
  );
}

function StatusDot({ tone }: { tone: 'positive' | 'warning' }) {
  return (
    <span
      data-testid="indexer-status-dot"
      aria-hidden="true"
      className={clsx(
        'inline-block h-1.5 w-1.5 rounded-full',
        tone === 'positive' ? 'bg-accent-positive' : 'bg-accent-warning',
      )}
    />
  );
}

function chipContent(chip: ChipKind): {
  body: React.ReactNode;
  title: string;
} {
  switch (chip.kind) {
    case 'idle':
      return {
        body: (
          <>
            <StatusDot tone="positive" />
            <span>Watching</span>
          </>
        ),
        title: 'Catalog up to date · Watching',
      };
    case 'scanning':
      return {
        body: (
          <>
            <Spinner />
            <span>Scanning {chip.done}/{chip.total}</span>
          </>
        ),
        title: `Scanning ${chip.done} of ${chip.total}`,
      };
    case 'backfilling':
      return {
        body: (
          <>
            <Spinner />
            <span>
              Catching up · {chip.name} {chip.done}/{chip.total}
            </span>
          </>
        ),
        title: `Catching up · ${chip.name} ${chip.done} of ${chip.total}`,
      };
    case 'watcher_warning':
      return {
        body: (
          <>
            <StatusDot tone="warning" />
            <span>polling every 5 min</span>
          </>
        ),
        title: chip.reason
          ? `Watching unavailable (${chip.reason}) · polling every 5 min`
          : 'Watching unavailable · polling every 5 min',
      };
  }
}

export function IndexerStatus() {
  const [state, dispatch] = useReducer(reducer, INITIAL);
  const onEvent = useCallback((ev: IndexerEvent) => dispatch(ev), []);
  useIndexerEvents(onEvent);
  const findings = useFindings();
  const chip = deriveChip(state);
  const { body, title } = chipContent(chip);
  const findingsCount = findings ? findings.macpath + findings.duplicates : 0;

  return (
    <div className="flex items-center gap-2">
      <span
        data-testid="indexer-status-chip"
        title={title}
        className={clsx(
          'inline-flex items-center gap-1.5 px-2 py-0.5 rounded-chip',
          'border border-rule-line bg-surface-card text-ink-secondary',
          'font-mono text-[11px] tracking-wide whitespace-nowrap',
        )}
      >
        {body}
      </span>
      {findingsCount > 0 ? (
        <span
          data-testid="indexer-findings-badge"
          title={
            findings
              ? `${findings.macpath} mac-path · ${findings.duplicates} duplicates`
              : undefined
          }
          className={clsx(
            'inline-flex items-center gap-1 px-2 py-0.5 rounded-chip',
            'border border-rule-line bg-surface-card text-ink-secondary',
            'font-mono text-[11px] tracking-wide whitespace-nowrap',
          )}
        >
          <span className="text-ink-primary tabular-nums">{findingsCount}</span>
          <span>findings</span>
        </span>
      ) : null}
    </div>
  );
}
