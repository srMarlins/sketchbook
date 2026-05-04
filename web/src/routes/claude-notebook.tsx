import { useJournal, useProjects } from '../app/queries';
import { LoadingState } from '../components/feedback/LoadingState';
import { EmptyState } from '../components/feedback/EmptyState';
import type { Project } from '../lib/types';

export function ClaudeJournal({ onOpenProject }: { onOpenProject: (project: Project) => void }) {
  const journal = useJournal();
  const projects = useProjects();

  if (journal.isLoading || projects.isLoading) return <LoadingState />;
  if (!journal.data || journal.data.length === 0) {
    return <EmptyState icon="cassette-tape" title="no journal entries yet" />;
  }

  const projectsById = new Map((projects.data ?? []).map((p) => [p.id, p] as const));

  return (
    <ol className="space-y-2">
      {journal.data
        .slice()
        .sort((a, b) => b.created_at - a.created_at)
        .map((j) => {
          const proj = j.project_id != null ? projectsById.get(j.project_id) : undefined;
          return (
            <li
              key={j.id}
              className="border-t border-rule-line/30 pt-2 flex flex-wrap items-baseline gap-x-3 gap-y-1"
            >
              <span className="font-display text-lg text-accent-action">{j.verb}</span>
              <button
                type="button"
                onClick={() => proj && onOpenProject(proj)}
                disabled={!proj}
                className="font-mono text-sm text-ink-primary hover:underline disabled:no-underline truncate max-w-md"
              >
                {proj?.name ?? j.target}
              </button>
              <span className="ml-auto font-mono text-xs text-ink-muted">
                {j.source} · {new Date(j.created_at * 1000).toISOString().slice(0, 10)}
              </span>
            </li>
          );
        })}
    </ol>
  );
}
