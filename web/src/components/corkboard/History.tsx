import { useJournal, useUndoBatch } from '../../app/queries';
import { translateJournal } from '../../lib/proposal-translate';
import { Button } from '../inputs/Button';
import { LoadingState } from '../feedback/LoadingState';
import { EmptyState } from '../feedback/EmptyState';

export function History({ projectId }: { projectId: number }) {
  const journal = useJournal();
  const undo = useUndoBatch();

  if (journal.isLoading) return <LoadingState />;

  const batches = (journal.data ?? []).filter((b) =>
    b.actions.some((a) => a.project_id === projectId),
  );

  if (batches.length === 0) {
    return <EmptyState icon="bookmark" title="no history yet" />;
  }
  return (
    <ol className="space-y-2 font-mono text-sm">
      {batches
        .slice()
        .reverse()
        .map((batch) => {
          const head = batch.actions.find((a) => a.project_id === projectId) ?? batch.actions[0]!;
          const tx = translateJournal(head);
          return (
            <li
              key={batch.batch_id}
              className="border-t border-rule-line pt-2 flex flex-wrap items-baseline gap-x-3 gap-y-1"
            >
              <span className="text-xs uppercase tracking-wider text-ink-secondary">
                {tx.verb}
              </span>
              <span className="text-ink-secondary truncate min-w-0 flex-1">{tx.after}</span>
              <span className="text-[11px] text-ink-muted">{batch.actor}</span>
              <Button variant="ghost" size="sm" onClick={() => undo.mutate(batch.batch_id)}>
                undo
              </Button>
            </li>
          );
        })}
    </ol>
  );
}
