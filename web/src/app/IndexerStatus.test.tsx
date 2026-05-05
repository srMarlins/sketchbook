import { type ReactNode } from 'react';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act } from 'react';
import { beforeEach, describe, expect, test } from 'vitest';
import { IndexerStatus } from './IndexerStatus';
import { findingsKey, type FindingsSummary } from '../hooks/useIndexerCachePatcher';

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  url: string;
  listeners: Record<string, ((m: MessageEvent) => void)[]> = {};
  closed = false;
  onopen: ((ev: Event) => void) | null = null;
  onerror: ((ev: Event) => void) | null = null;
  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }
  addEventListener(kind: string, h: (m: MessageEvent) => void) {
    (this.listeners[kind] ||= []).push(h);
  }
  close() {
    this.closed = true;
  }
  fire(kind: string, data: unknown) {
    (this.listeners[kind] || []).forEach((h) =>
      h(new MessageEvent(kind, { data: JSON.stringify(data) })),
    );
  }
  open() {
    this.onopen?.(new Event('open'));
  }
  error() {
    this.onerror?.(new Event('error'));
  }
}

beforeEach(() => {
  FakeEventSource.instances = [];
  (globalThis as unknown as { EventSource: typeof FakeEventSource }).EventSource =
    FakeEventSource;
});

function makeWrapper(qc: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
  };
}

function renderStatus(qc = new QueryClient()) {
  return {
    qc,
    ...render(<IndexerStatus />, { wrapper: makeWrapper(qc) }),
  };
}

describe('IndexerStatus', () => {
  test('a) idle state shows watching copy and green dot', () => {
    renderStatus();
    const chip = screen.getByTestId('indexer-status-chip');
    expect(chip.textContent).toMatch(/watching/i);
    const dot = screen.getByTestId('indexer-status-dot');
    expect(dot.className).toMatch(/bg-accent-positive/);
    expect(screen.queryByTestId('indexer-status-spinner')).toBeNull();
  });

  test('b) scanning state shows progress copy', () => {
    renderStatus();
    const es = FakeEventSource.instances[0]!;
    act(() => {
      es.fire('scan_started', {
        kind: 'scan_started',
        discovered: 1628,
        to_parse: 1628,
        missing: 0,
      });
      es.fire('scan_progress', { kind: 'scan_progress', done: 124, total: 1628 });
    });
    const chip = screen.getByTestId('indexer-status-chip');
    expect(chip.textContent).toMatch(/Scanning 124\/1628/);
    expect(screen.getByTestId('indexer-status-spinner')).toBeTruthy();
  });

  test('c) backfilling state shows column name and progress', () => {
    renderStatus();
    const es = FakeEventSource.instances[0]!;
    act(() => {
      es.fire('backfill_started', {
        kind: 'backfill_started',
        name: 'macpath',
        total: 200,
      });
      es.fire('backfill_progress', {
        kind: 'backfill_progress',
        name: 'macpath',
        done: 50,
        total: 200,
      });
    });
    const chip = screen.getByTestId('indexer-status-chip');
    expect(chip.textContent).toMatch(/Catching up · macpath 50\/200/);
    expect(screen.getByTestId('indexer-status-spinner')).toBeTruthy();
  });

  test('d) watcher_status polling shows yellow dot and polling copy', () => {
    renderStatus();
    const es = FakeEventSource.instances[0]!;
    act(() => {
      es.fire('watcher_status', {
        kind: 'watcher_status',
        mode: 'polling',
        reason: 'non-local drive',
      });
    });
    const chip = screen.getByTestId('indexer-status-chip');
    expect(chip.textContent).toMatch(/polling/i);
    const dot = screen.getByTestId('indexer-status-dot');
    expect(dot.className).toMatch(/bg-accent-warning/);
  });

  test('e) findings badge renders count when cache has findings > 0', () => {
    const qc = new QueryClient();
    qc.setQueryData<FindingsSummary>(findingsKey, { macpath: 5, duplicates: 0 });
    renderStatus(qc);
    const badge = screen.getByTestId('indexer-findings-badge');
    expect(badge.textContent).toMatch(/5/);
    expect(badge.textContent).toMatch(/findings/i);
  });

  test('e2) no findings badge when total is zero', () => {
    const qc = new QueryClient();
    qc.setQueryData<FindingsSummary>(findingsKey, { macpath: 0, duplicates: 0 });
    renderStatus(qc);
    expect(screen.queryByTestId('indexer-findings-badge')).toBeNull();
  });

  test('f) scan_finished returns chip to idle', () => {
    renderStatus();
    const es = FakeEventSource.instances[0]!;
    act(() => {
      es.fire('scan_started', {
        kind: 'scan_started',
        discovered: 10,
        to_parse: 10,
        missing: 0,
      });
    });
    expect(screen.getByTestId('indexer-status-chip').textContent).toMatch(/Scanning/);
    act(() => {
      es.fire('scan_finished', {
        kind: 'scan_finished',
        new: 10,
        updated: 0,
        unchanged: 0,
        missing: 0,
        failed: 0,
      });
    });
    const chip = screen.getByTestId('indexer-status-chip');
    expect(chip.textContent).toMatch(/watching/i);
    expect(screen.queryByTestId('indexer-status-spinner')).toBeNull();
  });

  test('h) onerror sets chip to disconnected with warning dot', () => {
    renderStatus();
    const es = FakeEventSource.instances[0]!;
    act(() => {
      es.error();
    });
    const chip = screen.getByTestId('indexer-status-chip');
    expect(chip.textContent).toMatch(/reconnecting/i);
    const dot = screen.getByTestId('indexer-status-dot');
    expect(dot.className).toMatch(/bg-accent-warning/);
  });

  test('i) disconnected overrides scanning state', () => {
    renderStatus();
    const es = FakeEventSource.instances[0]!;
    act(() => {
      es.fire('scan_started', {
        kind: 'scan_started',
        discovered: 100,
        to_parse: 100,
        missing: 0,
      });
      es.error();
    });
    const chip = screen.getByTestId('indexer-status-chip');
    expect(chip.textContent).toMatch(/reconnecting/i);
    expect(chip.textContent).not.toMatch(/Scanning/);
  });

  test('j) onopen after onerror clears disconnected state', () => {
    renderStatus();
    const es = FakeEventSource.instances[0]!;
    act(() => {
      es.error();
    });
    expect(screen.getByTestId('indexer-status-chip').textContent).toMatch(
      /reconnecting/i,
    );
    act(() => {
      es.open();
    });
    const chip = screen.getByTestId('indexer-status-chip');
    expect(chip.textContent).not.toMatch(/reconnecting/i);
    expect(chip.textContent).toMatch(/watching/i);
  });

  test('g) backfill takes priority over concurrent scan', () => {
    renderStatus();
    const es = FakeEventSource.instances[0]!;
    act(() => {
      es.fire('scan_started', {
        kind: 'scan_started',
        discovered: 100,
        to_parse: 100,
        missing: 0,
      });
      es.fire('backfill_started', {
        kind: 'backfill_started',
        name: 'macpath',
        total: 200,
      });
    });
    const chip = screen.getByTestId('indexer-status-chip');
    expect(chip.textContent).toMatch(/Catching up/);
    expect(chip.textContent).not.toMatch(/Scanning/);
  });
});
