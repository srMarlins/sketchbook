import { type ReactNode } from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act } from 'react';
import { beforeEach, describe, expect, test } from 'vitest';
import { FirstLaunch } from './FirstLaunch';
import { projectsKey } from './queries';
import type { ProjectSummary } from '../lib/types';

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  url: string;
  listeners: Record<string, ((m: MessageEvent) => void)[]> = {};
  closed = false;
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

function makeProjects(n: number): ProjectSummary[] {
  return Array.from({ length: n }, (_, i) => ({
    id: i + 1,
    path: `/tmp/p${i}.als`,
    name: `p${i}`,
    parent_dir: '/tmp',
    tempo: null,
    time_sig_num: null,
    time_sig_den: null,
    track_count: null,
    audio_tracks: null,
    midi_tracks: null,
    return_tracks: null,
    length_seconds: null,
    live_version: null,
    last_modified: 0,
    last_scanned: 0,
    file_hash: '',
    is_archived: 0,
    color_tag: null,
    notes: null,
    tags: [],
    effort_score: null,
  }) as unknown as ProjectSummary);
}

function renderSplash(opts: { count: number } = { count: 0 }) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  qc.setQueryData(projectsKey({}), makeProjects(opts.count));
  return {
    qc,
    ...render(<FirstLaunch />, { wrapper: makeWrapper(qc) }),
  };
}

describe('FirstLaunch', () => {
  test('a) hidden when count >= 30', () => {
    renderSplash({ count: 30 });
    expect(screen.queryByTestId('first-launch-splash')).toBeNull();
  });

  test('b) hidden before first scan event with empty catalog', () => {
    renderSplash({ count: 0 });
    expect(screen.queryByTestId('first-launch-splash')).toBeNull();
  });

  test('c) visible during scan with progress', () => {
    renderSplash({ count: 0 });
    const es = FakeEventSource.instances[0]!;
    act(() => {
      es.fire('scan_started', {
        kind: 'scan_started',
        discovered: 1628,
        to_parse: 1628,
        missing: 0,
      });
      es.fire('scan_progress', {
        kind: 'scan_progress',
        done: 100,
        total: 1628,
      });
    });
    expect(screen.getByTestId('first-launch-splash')).toBeTruthy();
    expect(screen.getByText(/welcome — indexing your library/i)).toBeTruthy();
    const bar = screen.getByTestId('first-launch-progress-fill');
    // 100/1628 ≈ 6.14%
    expect(bar.getAttribute('style')).toMatch(/width:\s*6\./);
  });

  test('d) shows currently-parsing path', () => {
    renderSplash({ count: 0 });
    const es = FakeEventSource.instances[0]!;
    act(() => {
      es.fire('scan_started', {
        kind: 'scan_started',
        discovered: 10,
        to_parse: 10,
        missing: 0,
      });
      es.fire('scan_row', {
        kind: 'scan_row',
        path: '/Volumes/x/foo Project/foo.als',
        status: 'new',
        project_id: 1,
      });
    });
    expect(screen.getByText('/Volumes/x/foo Project/foo.als')).toBeTruthy();
  });

  test('e) auto-dismisses when catalog reaches 30 rows', async () => {
    const { qc } = renderSplash({ count: 0 });
    const es = FakeEventSource.instances[0]!;
    act(() => {
      es.fire('scan_started', {
        kind: 'scan_started',
        discovered: 1628,
        to_parse: 1628,
        missing: 0,
      });
    });
    expect(screen.getByTestId('first-launch-splash')).toBeTruthy();
    qc.setQueryData(projectsKey({}), makeProjects(30));
    await waitFor(() => {
      expect(screen.queryByTestId('first-launch-splash')).toBeNull();
    });
  });

  test('f) hidden after scan_finished even with empty catalog', () => {
    renderSplash({ count: 0 });
    const es = FakeEventSource.instances[0]!;
    act(() => {
      es.fire('scan_started', {
        kind: 'scan_started',
        discovered: 0,
        to_parse: 0,
        missing: 0,
      });
    });
    act(() => {
      es.fire('scan_finished', {
        kind: 'scan_finished',
        new: 0,
        updated: 0,
        unchanged: 0,
        missing: 0,
        failed: 0,
      });
    });
    expect(screen.queryByTestId('first-launch-splash')).toBeNull();
  });

  test('g) progress bar handles total=0 gracefully', () => {
    renderSplash({ count: 0 });
    const es = FakeEventSource.instances[0]!;
    act(() => {
      es.fire('scan_started', {
        kind: 'scan_started',
        discovered: 0,
        to_parse: 0,
        missing: 0,
      });
    });
    // Splash visible (scanActive=true), bar should not throw NaN/Infinity
    const bar = screen.getByTestId('first-launch-progress-fill');
    const style = bar.getAttribute('style') || '';
    expect(style).toMatch(/width:\s*\d+(\.\d+)?%/);
    expect(style).not.toMatch(/NaN|Infinity/);
  });
});
