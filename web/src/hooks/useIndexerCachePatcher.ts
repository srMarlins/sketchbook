import { useCallback } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useIndexerEvents, type IndexerEvent } from './useIndexerEvents';

export type FindingsSummary = { macpath: number; duplicates: number };

export const findingsKey = ['findings'] as const;

/**
 * Subscribes to the indexer SSE stream and patches the TanStack cache
 * in response to events. This is the bridge between push-based backend
 * indexer events and the pull-based query cache the UI reads from.
 *
 * - `scan_row` with `project_id`: invalidate that project + the projects
 *   list (partial-key invalidation matches `useApproveProposal`).
 * - `findings_changed`: write the summary directly into `['findings']`;
 *   no fetch needed since the event payload carries the numbers.
 */
export function useIndexerCachePatcher() {
  const qc = useQueryClient();
  const onEvent = useCallback(
    (ev: IndexerEvent) => {
      if (ev.kind === 'scan_row' && ev.project_id != null) {
        qc.invalidateQueries({ queryKey: ['project', ev.project_id] });
        qc.invalidateQueries({ queryKey: ['projects'] });
      } else if (ev.kind === 'findings_changed') {
        const { macpath, duplicates } = ev;
        qc.setQueryData<FindingsSummary>(findingsKey, { macpath, duplicates });
      }
    },
    [qc],
  );
  useIndexerEvents(onEvent);
}
