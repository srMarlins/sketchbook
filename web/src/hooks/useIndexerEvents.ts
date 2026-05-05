import { useEffect } from 'react';

export type IndexerEvent =
  | { kind: 'hello'; ts: number }
  | { kind: 'scan_started'; discovered: number; to_parse: number; missing: number }
  | {
      kind: 'scan_row';
      project_id?: number;
      path: string;
      status: 'new' | 'updated' | 'skipped' | 'failed' | 'missing';
      error?: string;
    }
  | { kind: 'scan_progress'; done: number; total: number }
  | {
      kind: 'scan_finished';
      new: number;
      updated: number;
      unchanged: number;
      missing: number;
      failed: number;
    }
  | { kind: 'backfill_started'; name: string; total: number }
  | { kind: 'backfill_progress'; name: string; done: number; total: number }
  | { kind: 'backfill_finished'; name: string; done: number; failed: number }
  | { kind: 'watcher_status'; mode: 'watching' | 'polling' | 'off'; reason?: string }
  | { kind: 'findings_changed'; macpath: number; duplicates: number };

export function useIndexerEvents(onEvent: (ev: IndexerEvent) => void) {
  useEffect(() => {
    const es = new EventSource('/api/events');
    const onMsg = (m: MessageEvent) => {
      try {
        onEvent(JSON.parse(m.data));
      } catch {
        /* ignore malformed events */
      }
    };
    es.addEventListener('scan_started', onMsg);
    es.addEventListener('scan_row', onMsg);
    es.addEventListener('scan_progress', onMsg);
    es.addEventListener('scan_finished', onMsg);
    es.addEventListener('backfill_started', onMsg);
    es.addEventListener('backfill_progress', onMsg);
    es.addEventListener('backfill_finished', onMsg);
    es.addEventListener('watcher_status', onMsg);
    es.addEventListener('findings_changed', onMsg);
    return () => es.close();
  }, [onEvent]);
}
