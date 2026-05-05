import { renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, test, vi } from 'vitest';
import { useIndexerEvents, type IndexerEvent } from './useIndexerEvents';

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
  fireRaw(kind: string, raw: string) {
    (this.listeners[kind] || []).forEach((h) =>
      h(new MessageEvent(kind, { data: raw })),
    );
  }
}

beforeEach(() => {
  FakeEventSource.instances = [];
  (globalThis as unknown as { EventSource: typeof FakeEventSource }).EventSource =
    FakeEventSource;
});

describe('useIndexerEvents', () => {
  test('subscribes to /api/events on mount', () => {
    const onEvent = vi.fn();
    renderHook(() => useIndexerEvents(onEvent));
    expect(FakeEventSource.instances).toHaveLength(1);
    expect(FakeEventSource.instances[0]!.url).toBe('/api/events');
  });

  test('calls onEvent with parsed payload for each kind', () => {
    const onEvent = vi.fn();
    renderHook(() => useIndexerEvents(onEvent));
    const es = FakeEventSource.instances[0]!;

    const scanStarted: IndexerEvent = {
      kind: 'scan_started',
      discovered: 10,
      to_parse: 5,
      missing: 1,
    };
    const scanRow: IndexerEvent = {
      kind: 'scan_row',
      path: '/tmp/foo.als',
      status: 'new',
    };
    const findings: IndexerEvent = {
      kind: 'findings_changed',
      macpath: 3,
      duplicates: 7,
    };

    es.fire('scan_started', scanStarted);
    es.fire('scan_row', scanRow);
    es.fire('findings_changed', findings);

    expect(onEvent).toHaveBeenCalledTimes(3);
    expect(onEvent).toHaveBeenNthCalledWith(1, scanStarted);
    expect(onEvent).toHaveBeenNthCalledWith(2, scanRow);
    expect(onEvent).toHaveBeenNthCalledWith(3, findings);
  });

  test('closes EventSource on unmount', () => {
    const onEvent = vi.fn();
    const { unmount } = renderHook(() => useIndexerEvents(onEvent));
    const es = FakeEventSource.instances[0]!;
    expect(es.closed).toBe(false);
    unmount();
    expect(es.closed).toBe(true);
  });

  test('malformed JSON does not throw and does not call onEvent', () => {
    const onEvent = vi.fn();
    renderHook(() => useIndexerEvents(onEvent));
    const es = FakeEventSource.instances[0]!;

    expect(() => es.fireRaw('scan_progress', '{{not-json')).not.toThrow();
    expect(onEvent).not.toHaveBeenCalled();
  });
});
