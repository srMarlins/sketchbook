import { useNavigate, useParams } from '@tanstack/react-router';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import { BrandingHeader } from '../components/surface/BrandingHeader';
import { Desk } from '../components/surface/Desk';
import { Sidebar } from '../components/surface/Sidebar';
import { NotebookPage } from '../components/surface/NotebookPage';
import { SongStrip } from '../components/data/SongStrip';
import { SearchBar, useSearchStore } from '../components/inputs/SearchBar';
import { ProjectCorkboard } from '../components/corkboard';
import { LoadingState } from '../components/feedback/LoadingState';
import { ErrorState } from '../components/feedback/ErrorState';
import { EmptyState } from '../components/feedback/EmptyState';
import { useKeyboard } from '../hooks/useKeyboard';
import { useProjects, useProposals } from '../app/queries';
import { deriveNotebooks } from '../app/notebooks';
import type { ProjectSummary } from '../lib/types';
import { ClaudeJournal } from './claude-notebook';

export function NotebookRoute() {
  const params = useParams({ strict: false }) as { notebookId?: string };
  const notebookId = params.notebookId ?? '';
  const navigate = useNavigate();
  const projectsQ = useProjects();
  const proposals = useProposals();
  const query = useSearchStore((s) => s.query);
  const clear = useSearchStore((s) => s.clear);

  const [openProjectId, setOpenProjectId] = useState<number | null>(null);
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
        p.tags.some((t) => t.toLowerCase().includes(lower)),
    );
  }, [projectsQ.data, notebook, query, notebookId]);

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
              badge: proposals.data?.length || null,
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
        kind="plain"
        header={
          <div className="flex items-baseline gap-3 flex-wrap">
            <h2 className="text-xl font-semibold tracking-tight">
              {notebookId === 'claude' ? 'Claude' : notebook?.title ?? notebookId}
            </h2>
            <span className="font-mono text-xs text-ink-muted">
              {notebookId === 'claude'
                ? ''
                : `${filtered.length} project${filtered.length === 1 ? '' : 's'}`}
            </span>
          </div>
        }
      >
        {notebookId === 'claude' ? (
          <ClaudeJournal
            onOpenProject={(p) => {
              setOpenProjectId(p.id);
              setCorkOpen(true);
            }}
          />
        ) : projectsQ.isLoading ? (
          <LoadingState />
        ) : projectsQ.isError ? (
          <ErrorState body={String(projectsQ.error)} onRetry={() => projectsQ.refetch()} />
        ) : filtered.length === 0 ? (
          <EmptyState
            title={query ? 'no matches' : 'empty notebook'}
            body={query ? `nothing matches "${query}"` : 'this notebook has no projects yet'}
          />
        ) : (
          <VirtualStrips
            projects={filtered}
            onOpen={(p) => {
              setOpenProjectId(p.id);
              setCorkOpen(true);
            }}
          />
        )}
      </NotebookPage>
      <ProjectCorkboard projectId={openProjectId} open={corkOpen} onOpenChange={setCorkOpen} />
    </Desk>
  );
}

function VirtualStrips({
  projects,
  onOpen,
}: {
  projects: ProjectSummary[];
  onOpen: (project: ProjectSummary) => void;
}) {
  const parentRef = useRef<HTMLDivElement>(null);

  const rowVirtualizer = useVirtualizer({
    count: projects.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => 56,
    overscan: 8,
  });

  return (
    <div ref={parentRef} className="relative max-h-[72vh] overflow-y-auto pr-1">
      <div style={{ height: rowVirtualizer.getTotalSize(), position: 'relative' }}>
        {rowVirtualizer.getVirtualItems().map((vRow) => {
          const project = projects[vRow.index]!;
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
                paddingTop: 4,
                paddingBottom: 4,
              }}
            >
              <SongStrip project={project} onOpen={() => onOpen(project)} />
            </div>
          );
        })}
      </div>
    </div>
  );
}
