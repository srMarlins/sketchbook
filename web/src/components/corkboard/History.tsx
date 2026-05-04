import { useJournal } from '../../app/queries';
import { LoadingState } from '../feedback/LoadingState';
import { EmptyState } from '../feedback/EmptyState';

export function History({ projectId }: { projectId: number }) {
  const journal = useJournal(projectId);
  if (journal.isLoading) return <LoadingState />;
  if (!journal.data || journal.data.length === 0) {
    return <EmptyState icon="bookmark" title="no history yet" />;
  }
  return (
    <ol className="space-y-2 font-mono text-sm">
      {journal.data
        .slice()
        .sort((a, b) => b.created_at - a.created_at)
        .map((j) => (
          <li key={j.id} className="border-t border-rule-line/30 pt-2">
            <span className="font-display text-lg text-accent-action mr-2">{j.verb}</span>
            <span className="text-ink-muted">{j.target}</span>
            <span className="float-right text-xs text-ink-muted">
              {j.source} · {new Date(j.created_at * 1000).toISOString().slice(0, 10)}
            </span>
          </li>
        ))}
    </ol>
  );
}
