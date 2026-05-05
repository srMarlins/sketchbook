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
import { useCategory, useHome, useOpenProject, useProjects, useProposals } from '../app/queries';
import { deriveNotebooks } from '../app/notebooks';
import { deriveProjectGroups, type ProjectGroup } from '../lib/project-groups';
import type { ProjectSummary } from '../lib/types';
import { ClaudeJournal } from './claude-notebook';

export function NotebookRoute() {
  const params = useParams({ strict: false }) as { notebookId?: string };
  const notebookId = params.notebookId ?? '';
  const navigate = useNavigate();
  const projectsQ = useProjects({ limit: 1000 });
  const proposals = useProposals();
  const homeQ = useHome();
  const openProjectMut = useOpenProject();
  const query = useSearchStore((s) => s.query);
  const clear = useSearchStore((s) => s.clear);

  const [openProjectId, setOpenProjectId] = useState<number | null>(null);
  const [corkOpen, setCorkOpen] = useState(false);

  const isCategory = notebookId.startsWith('cat-');
  const categoryId = isCategory ? notebookId.slice('cat-'.length) : null;
  const categoryQ = useCategory(categoryId);

  const notebooks = useMemo(
    () => (projectsQ.data ? deriveNotebooks(projectsQ.data) : []),
    [projectsQ.data],
  );
  const notebook = notebooks.find((n) => n.id === notebookId);

  const categoryShelf = useMemo(() => {
    if (!categoryId || !homeQ.data) return null;
    return homeQ.data.shelves.find((s) => s.id === categoryId) ?? null;
  }, [categoryId, homeQ.data]);

  const filtered = useMemo(() => {
    if (notebookId === 'claude') return [];
    let list: ProjectSummary[];
    if (isCategory) {
      // Full category set (uncapped, all variants) from /api/categories/{id}
      list = categoryQ.data ?? [];
    } else {
      if (!projectsQ.data) return [];
      list = projectsQ.data.filter(notebook?.filter ?? (() => true));
    }
    if (!query) return list;
    const lower = query.toLowerCase();
    return list.filter(
      (p) =>
        p.name.toLowerCase().includes(lower) ||
        p.path.toLowerCase().includes(lower) ||
        p.tags.some((t) => t.toLowerCase().includes(lower)),
    );
  }, [projectsQ.data, notebook, query, notebookId, isCategory, categoryQ.data]);

  const groups = useMemo(() => deriveProjectGroups(filtered), [filtered]);

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
          activeId={notebookId === 'claude' ? 'claude' : 'home'}
          items={[
            { id: 'home', label: 'Home', icon: 'house' as const },
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
              {notebookId === 'claude'
                ? 'Claude'
                : isCategory
                  ? categoryShelf?.title ?? categoryId ?? notebookId
                  : notebook?.title ?? notebookId}
            </h2>
            <span className="font-mono text-xs text-ink-muted">
              {notebookId === 'claude'
                ? ''
                : `${filtered.length} project${filtered.length === 1 ? '' : 's'} · ${groups.length} group${groups.length === 1 ? '' : 's'}`}
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
        ) : (isCategory ? categoryQ.isLoading : projectsQ.isLoading) ? (
          <LoadingState />
        ) : (isCategory ? categoryQ.isError : projectsQ.isError) ? (
          <ErrorState
            body={String(isCategory ? categoryQ.error : projectsQ.error)}
            onRetry={() => (isCategory ? categoryQ.refetch() : projectsQ.refetch())}
          />
        ) : filtered.length === 0 ? (
          <EmptyState
            title={query ? 'no matches' : 'empty notebook'}
            body={query ? `nothing matches "${query}"` : 'this notebook has no projects yet'}
          />
        ) : (
          <GroupedStrips
            groups={groups}
            onOpen={(p) => {
              setOpenProjectId(p.id);
              setCorkOpen(true);
            }}
            onLaunch={(id) => openProjectMut.mutate(id)}
          />
        )}
      </NotebookPage>
      <ProjectCorkboard projectId={openProjectId} open={corkOpen} onOpenChange={setCorkOpen} />
    </Desk>
  );
}

interface FlatRow {
  kind: 'header' | 'variant';
  group: ProjectGroup;
  project?: ProjectSummary;
  /** Stable key. */
  key: string;
}

function flattenGroups(groups: ProjectGroup[]): FlatRow[] {
  const rows: FlatRow[] = [];
  for (const g of groups) {
    if (g.variant_count > 1) {
      rows.push({ kind: 'header', group: g, key: `h:${g.id}` });
      for (const v of g.variants) {
        rows.push({ kind: 'variant', group: g, project: v, key: `v:${v.id}` });
      }
    } else {
      rows.push({ kind: 'variant', group: g, project: g.representative, key: `v:${g.representative.id}` });
    }
  }
  return rows;
}

function GroupedStrips({
  groups,
  onOpen,
  onLaunch,
}: {
  groups: ProjectGroup[];
  onOpen: (project: ProjectSummary) => void;
  onLaunch: (projectId: number) => void;
}) {
  const parentRef = useRef<HTMLDivElement>(null);
  const rows = useMemo(() => flattenGroups(groups), [groups]);

  const rowVirtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => parentRef.current,
    estimateSize: (index) => (rows[index]?.kind === 'header' ? 32 : 68),
    overscan: 8,
  });

  return (
    <div ref={parentRef} className="relative max-h-[72vh] overflow-y-auto pr-1">
      <div style={{ height: rowVirtualizer.getTotalSize(), position: 'relative' }}>
        {rowVirtualizer.getVirtualItems().map((vRow) => {
          const row = rows[vRow.index]!;
          return (
            <div
              key={row.key}
              data-index={vRow.index}
              data-row-kind={row.kind}
              style={{
                position: 'absolute',
                top: 0,
                left: 0,
                width: '100%',
                transform: `translateY(${vRow.start}px)`,
                paddingTop: row.kind === 'header' ? 8 : 4,
                paddingBottom: row.kind === 'header' ? 0 : 4,
              }}
            >
              {row.kind === 'header' ? (
                <div className="flex items-baseline gap-2 pl-1 pb-1 border-b border-rule-line/60">
                  <span className="font-display text-sm text-ink-primary">{row.group.title}</span>
                  <span className="font-mono text-[11px] text-ink-muted">
                    {row.group.variant_count} variants
                  </span>
                </div>
              ) : (
                <div className={row.group.variant_count > 1 ? 'pl-3' : ''}>
                  <SongStrip
                    project={row.project!}
                    onOpen={() => onOpen(row.project!)}
                    onLaunch={onLaunch}
                  />
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
