import { useJournal, useProjects, useUndoBatch } from '../app/queries';
import { LoadingState } from '../components/feedback/LoadingState';
import { EmptyState } from '../components/feedback/EmptyState';
import { Button } from '../components/inputs/Button';
import { translateJournal } from '../lib/proposal-translate';
import type { ProjectSummary } from '../lib/types';

export function ClaudeJournal({
  onOpenProject,
}: {
  onOpenProject: (project: ProjectSummary) => void;
}) {
  const journal = useJournal();
  const projects = useProjects();
  const undo = useUndoBatch();

  if (journal.isLoading || projects.isLoading) return <LoadingState />;
  if (!journal.data || journal.data.length === 0) {
    return (
      <EmptyState
        icon="cassette-tape"
        title="no history yet"
        body="approved proposals show up here as a journal of changes."
      />
    );
  }

  const projectsById = new Map((projects.data ?? []).map((p) => [p.id, p] as const));

  return (
    <ol className="space-y-3">
      {journal.data
        .slice()
        .reverse()
        .map((batch) => {
          const head = batch.actions[0];
          const project = head ? projectsById.get(head.project_id) : undefined;
          const tx = head ? translateJournal(head) : null;
          const extra = batch.actions.length - 1;
          return (
            <li
              key={batch.batch_id}
              className="rounded-card border border-rule-line bg-surface-card shadow-card overflow-hidden"
            >
              <header className="flex items-baseline gap-3 px-4 py-2 border-b border-rule-line bg-paper-tint-cream">
                <span className="text-xs font-mono uppercase tracking-wider text-ink-secondary">
                  {tx?.verb ?? 'batch'}
                </span>
                <button
                  type="button"
                  onClick={() => project && onOpenProject(project)}
                  disabled={!project}
                  className="text-sm font-medium text-ink-primary hover:underline disabled:no-underline truncate min-w-0 flex-1 text-left"
                >
                  {project?.name ?? `project #${head?.project_id}`}
                </button>
                {extra > 0 ? (
                  <span className="text-[11px] font-mono text-ink-muted">+{extra} more</span>
                ) : null}
                <span className="text-[11px] font-mono text-ink-muted">{batch.actor}</span>
              </header>
              {tx ? (
                <div className="px-4 py-2 grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1 font-mono text-[12px]">
                  <span className="text-ink-muted">before</span>
                  <span className="text-ink-secondary truncate">{tx.before}</span>
                  <span className="text-ink-muted">after</span>
                  <span className="text-accent-positive truncate">{tx.after}</span>
                </div>
              ) : null}
              <footer className="flex items-center gap-2 px-4 py-1.5 border-t border-rule-line">
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => undo.mutate(batch.batch_id)}
                  disabled={undo.isPending}
                >
                  undo
                </Button>
                <span className="ml-auto text-[10px] font-mono text-ink-muted truncate">
                  {batch.batch_id}
                </span>
              </footer>
            </li>
          );
        })}
    </ol>
  );
}
