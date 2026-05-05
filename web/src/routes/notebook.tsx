import { useNavigate, useParams } from '@tanstack/react-router';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import { BrandingHeader } from '../components/surface/BrandingHeader';
import { Desk } from '../components/surface/Desk';
import { Sidebar } from '../components/surface/Sidebar';
import { NotebookPage } from '../components/surface/NotebookPage';
import { SongStrip } from '../components/data/SongStrip';
import { Sprite } from '../components/primitives/Sprite';
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

type Row =
  | { kind: 'group'; group: ProjectGroup; key: string }
  | { kind: 'standalone'; project: ProjectSummary; key: string };

function buildRows(groups: ProjectGroup[]): Row[] {
  return groups.map((g) =>
    g.variant_count > 1
      ? { kind: 'group', group: g, key: `g:${g.id}` }
      : { kind: 'standalone', project: g.representative, key: `s:${g.representative.id}` },
  );
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
  const rows = useMemo(() => buildRows(groups), [groups]);

  // Each row is a whole group (or a standalone). Heights vary widely (a
  // group with 8 variants is much taller than a standalone), so we let the
  // virtualizer measure rendered DOM nodes instead of guessing — this is
  // what fixes the "spacing isn't even" drift from a fixed estimate.
  const rowVirtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => parentRef.current,
    estimateSize: (index) => {
      const r = rows[index];
      if (!r) return 96;
      if (r.kind === 'standalone') return 96;
      // Group: header (~44) + N variants (~64 each) + closing border. Add
      // 18px gap above + 18 below.
      return 44 + r.group.variant_count * 64 + 36;
    },
    overscan: 4,
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
              ref={rowVirtualizer.measureElement}
              style={{
                position: 'absolute',
                top: 0,
                left: 0,
                width: '100%',
                transform: `translateY(${vRow.start}px)`,
                paddingTop: 9,
                paddingBottom: 9,
              }}
            >
              {row.kind === 'group' ? <GroupBlock group={row.group} onOpen={onOpen} onLaunch={onLaunch} /> : (
                <SongStrip
                  project={row.project}
                  onOpen={() => onOpen(row.project)}
                  onLaunch={onLaunch}
                />
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

/**
 * One multi-variant project rendered as a single bordered block:
 * tinted bg, header row with folder icon + title + variant count,
 * variants stacked with a left accent rule. Single border around the
 * whole block — no per-variant border stacking, no seam artifacts.
 */
function GroupBlock({
  group,
  onOpen,
  onLaunch,
}: {
  group: ProjectGroup;
  onOpen: (project: ProjectSummary) => void;
  onLaunch: (projectId: number) => void;
}) {
  return (
    <div className="bg-paper-tint-cream border border-rule-line-strong rounded-card shadow-card overflow-hidden">
      <div className="flex items-center gap-2 px-3 py-2 border-b border-rule-line-strong/60">
        <Sprite name="folder" size={14} className="text-accent shrink-0" />
        <span className="font-display text-[14px] font-semibold text-ink-primary truncate">
          {group.title}
        </span>
        <span className="font-mono text-[10px] uppercase tracking-wider text-ink-muted shrink-0">
          · {group.variant_count} variants
        </span>
      </div>
      <div className="relative pl-2 pr-2 py-2 space-y-1">
        <span aria-hidden className="absolute left-0 top-2 bottom-2 w-0.5 bg-accent-soft" />
        {group.variants.map((v) => (
          <SongStrip
            key={v.id}
            project={v}
            onOpen={() => onOpen(v)}
            onLaunch={onLaunch}
          />
        ))}
      </div>
    </div>
  );
}
