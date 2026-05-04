import { useNavigate, useParams } from '@tanstack/react-router';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import { BrandingHeader } from '../components/surface/BrandingHeader';
import { Desk } from '../components/surface/Desk';
import { Sidebar } from '../components/surface/Sidebar';
import { NotebookPage } from '../components/surface/NotebookPage';
import { SongStrip } from '../components/data/SongStrip';
import { MarginStickyNote } from '../components/data/MarginStickyNote';
import { SearchBar, useSearchStore } from '../components/inputs/SearchBar';
import { ProjectCorkboard } from '../components/corkboard';
import { LoadingState } from '../components/feedback/LoadingState';
import { ErrorState } from '../components/feedback/ErrorState';
import { EmptyState } from '../components/feedback/EmptyState';
import { useKeyboard } from '../hooks/useKeyboard';
import { useProjects, useProposals, useSuggestions } from '../app/queries';
import { deriveNotebooks } from '../app/notebooks';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { Project, Suggestion } from '../lib/types';
import { ClaudeJournal } from './claude-notebook';

export function NotebookRoute() {
  const params = useParams({ strict: false }) as { notebookId?: string };
  const notebookId = params.notebookId ?? '';
  const navigate = useNavigate();
  const projectsQ = useProjects();
  const proposals = useProposals();
  const suggestions = useSuggestions();
  const query = useSearchStore((s) => s.query);
  const clear = useSearchStore((s) => s.clear);

  const [openProject, setOpenProject] = useState<Project | null>(null);
  const [corkOpen, setCorkOpen] = useState(false);

  const notebooks = useMemo(
    () => (projectsQ.data ? deriveNotebooks(projectsQ.data) : []),
    [projectsQ.data],
  );
  const notebook = notebooks.find((n) => n.id === notebookId);

  const filtered = useMemo(() => {
    if (!projectsQ.data) return [];
    if (notebookId === 'claude') return [];
    const list = projectsQ.data.filter(notebook?.filter ?? (() => true));
    if (!query) return list;
    const lower = query.toLowerCase();
    return list.filter(
      (p) =>
        p.name.toLowerCase().includes(lower) ||
        p.path.toLowerCase().includes(lower) ||
        p.tags.some((t) => t.includes(lower)),
    );
  }, [projectsQ.data, notebook, query, notebookId]);

  // Esc cascade: close corkboard first; if closed, clear search
  useKeyboard({
    combo: 'esc',
    priority: 5,
    handler: () => {
      if (corkOpen) {
        setCorkOpen(false);
        return false;
      }
      if (query) {
        clear();
        return false;
      }
      return undefined;
    },
  });

  useEffect(() => {
    return () => clear();
  }, [clear]);

  return (
    <Desk
      branding={<BrandingHeader />}
      search={<SearchBar />}
      sidebar={
        <Sidebar
          activeId={notebookId === 'claude' ? 'claude' : 'projects'}
          items={[
            { id: 'home', label: 'Home', icon: 'house' as const },
            { id: 'projects', label: 'Projects', icon: 'folder' as const },
            {
              id: 'proposals',
              label: 'Proposals',
              icon: 'paper-airplane' as const,
              badge: proposals.data?.filter((p) => p.status === 'pending').length || null,
            },
            { id: 'claude', label: 'Claude', icon: 'cassette-tape' as const },
          ]}
          onActivate={(id) => {
            if (id === 'home') void navigate({ to: '/' });
            if (id === 'proposals') void navigate({ to: '/proposals' });
            if (id === 'claude') void navigate({ to: '/n/$notebookId', params: { notebookId: 'claude' } });
          }}
        />
      }
    >
      <NotebookPage
        kind={notebookId === 'claude' ? 'kraft' : notebook?.kind ?? 'lined'}
        header={
          <div className="flex items-baseline gap-3 flex-wrap">
            <h2 className="font-display text-3xl">{notebookId === 'claude' ? 'Claude' : notebook?.title ?? notebookId}</h2>
            <span className="font-mono text-sm text-ink-muted">
              {notebookId === 'claude' ? '' : `${filtered.length} sketch${filtered.length === 1 ? '' : 'es'}`}
            </span>
          </div>
        }
      >
        {notebookId === 'claude' ? (
          <ClaudeJournal onOpenProject={(p) => { setOpenProject(p); setCorkOpen(true); }} />
        ) : projectsQ.isLoading ? (
          <LoadingState />
        ) : projectsQ.isError ? (
          <ErrorState body={String(projectsQ.error)} onRetry={() => projectsQ.refetch()} />
        ) : filtered.length === 0 ? (
          <EmptyState
            title={query ? 'no matches' : 'empty notebook'}
            body={query ? `nothing matches "${query}"` : 'this notebook has no sketches yet'}
          />
        ) : (
          <VirtualStrips
            projects={filtered}
            suggestions={suggestions.data ?? []}
            onOpen={(project) => {
              setOpenProject(project);
              setCorkOpen(true);
            }}
          />
        )}
      </NotebookPage>
      <ProjectCorkboard project={openProject} open={corkOpen} onOpenChange={setCorkOpen} />
    </Desk>
  );
}

function VirtualStrips({
  projects,
  suggestions,
  onOpen,
}: {
  projects: Project[];
  suggestions: Suggestion[];
  onOpen: (project: Project) => void;
}) {
  const parentRef = useRef<HTMLDivElement>(null);
  const qc = useQueryClient();
  const enqueueAsProposal = useMutation({
    // Mock: re-approve the first matching proposal as a stand-in for "queue this suggestion"
    mutationFn: async (_id: string) => Promise.resolve(),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['proposals'] }),
  });

  const rowVirtualizer = useVirtualizer({
    count: projects.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => 64,
    overscan: 8,
  });

  const sBy = useMemo(() => {
    const m = new Map<number, Suggestion>();
    for (const s of suggestions) m.set(s.project_id, s);
    return m;
  }, [suggestions]);

  return (
    <div ref={parentRef} className="relative max-h-[70vh] overflow-y-auto pr-2">
      <div style={{ height: rowVirtualizer.getTotalSize(), position: 'relative' }}>
        {rowVirtualizer.getVirtualItems().map((vRow) => {
          const project = projects[vRow.index]!;
          const sug = sBy.get(project.id);
          return (
            <div
              key={project.id}
              data-index={vRow.index}
              style={{
                position: 'absolute',
                top: 0,
                left: 0,
                width: '100%',
                transform: `translateY(${vRow.start}px)`,
              }}
            >
              <div className="flex items-start gap-3">
                <div className="flex-1 py-1">
                  <SongStrip project={project} onOpen={() => onOpen(project)} />
                </div>
                {sug ? (
                  <MarginStickyNote
                    id={`sug-${project.id}`}
                    text={sug.text}
                    onOpenSuggestion={() => enqueueAsProposal.mutate(`sug-${project.id}`)}
                  />
                ) : null}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
