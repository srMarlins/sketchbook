import { type ReactNode } from 'react';
import { renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, test, vi } from 'vitest';
import {
  findingsKey,
  useIndexerCachePatcher,
  type FindingsSummary,
} from './useIndexerCachePatcher';

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

describe('useIndexerCachePatcher', () => {
  test('scan_row with project_id invalidates ["project", id] and ["projects"]', () => {
    const qc = new QueryClient();
    const spy = vi.spyOn(qc, 'invalidateQueries');
    renderHook(() => useIndexerCachePatcher(), { wrapper: makeWrapper(qc) });
    const es = FakeEventSource.instances[0]!;

    es.fire('scan_row', {
      kind: 'scan_row',
      project_id: 7,
      path: '/tmp/foo.als',
      status: 'updated',
    });

    expect(spy).toHaveBeenCalledWith({ queryKey: ['project', 7] });
    expect(spy).toHaveBeenCalledWith({ queryKey: ['projects'] });
  });

  test('scan_row without project_id does NOT invalidate', () => {
    const qc = new QueryClient();
    const spy = vi.spyOn(qc, 'invalidateQueries');
    renderHook(() => useIndexerCachePatcher(), { wrapper: makeWrapper(qc) });
    const es = FakeEventSource.instances[0]!;

    es.fire('scan_row', {
      kind: 'scan_row',
      path: '/tmp/broken.als',
      status: 'failed',
      error: 'parse error',
    });

    expect(spy).not.toHaveBeenCalled();
  });

  test('findings_changed writes ["findings"] cache entry', () => {
    const qc = new QueryClient();
    renderHook(() => useIndexerCachePatcher(), { wrapper: makeWrapper(qc) });
    const es = FakeEventSource.instances[0]!;

    es.fire('findings_changed', {
      kind: 'findings_changed',
      macpath: 5,
      duplicates: 2,
    });

    expect(qc.getQueryData<FindingsSummary>(findingsKey)).toEqual({
      macpath: 5,
      duplicates: 2,
    });
  });

  test('subsequent findings_changed overwrites prior value', () => {
    const qc = new QueryClient();
    renderHook(() => useIndexerCachePatcher(), { wrapper: makeWrapper(qc) });
    const es = FakeEventSource.instances[0]!;

    es.fire('findings_changed', {
      kind: 'findings_changed',
      macpath: 1,
      duplicates: 1,
    });
    es.fire('findings_changed', {
      kind: 'findings_changed',
      macpath: 9,
      duplicates: 3,
    });

    expect(qc.getQueryData<FindingsSummary>(findingsKey)).toEqual({
      macpath: 9,
      duplicates: 3,
    });
  });
});
