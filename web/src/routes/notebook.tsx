import { useNavigate, useParams } from '@tanstack/react-router';
import { useEffect, useMemo, useRef, useState } from 'react';
import clsx from 'clsx';
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

interface FlatRow {
  kind: 'header' | 'variant' | 'standalone';
  group: ProjectGroup;
  project?: ProjectSummary;
  /** For multi-variant rows: position within the group, used to round corners
   * + add bottom border on the last variant. Undefined for headers and standalone. */
  variantPos?: 'first' | 'middle' | 'last';
  /** Stable key. */
  key: string;
}

function flattenGroups(groups: ProjectGroup[]): FlatRow[] {
  const rows: FlatRow[] = [];
  for (const g of groups) {
    if (g.variant_count > 1) {
      rows.push({ kind: 'header', group: g, key: `h:${g.id}` });
      g.variants.forEach((v, i) => {
        const isFirst = i === 0;
        const isLast = i === g.variants.length - 1;
        rows.push({
          kind: 'variant',
          group: g,
          project: v,
          variantPos: isFirst ? 'first' : isLast ? 'last' : 'middle',
          key: `v:${v.id}`,
        });
      });
    } else {
      rows.push({
        kind: 'standalone',
        group: g,
        project: g.representative,
        key: `s:${g.representative.id}`,
      });
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
    estimateSize: (index) => {
      const r = rows[index];
      if (!r) return 84;
      // Header has bigger top breathing room so groups visibly start a section.
      if (r.kind === 'header') return 46;
      // Standalone rows get extra padding top + bottom to clearly stand apart
      // from neighboring group containers / other standalones.
      if (r.kind === 'standalone') return 92;
      // Variants are densely stacked inside a group container; last gets a
      // closing pad-bottom that bumps its size.
      if (r.variantPos === 'last') return 70;
      return 64;
    },
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
              data-variant-pos={row.variantPos ?? ''}
              style={{
                position: 'absolute',
                top: 0,
                left: 0,
                width: '100%',
                transform: `translateY(${vRow.start}px)`,
                paddingTop:
                  row.kind === 'header'
                    ? 18
                    : row.kind === 'standalone'
                      ? 18
                      : 0,
                paddingBottom:
                  row.kind === 'standalone'
                    ? 8
                    : row.kind === 'variant' && row.variantPos === 'last'
                      ? 8
                      : 0,
              }}
            >
              {row.kind === 'header' ? (
                // Multi-variant group header: full-opacity paper-tint
                // background with a small drop shadow so the group container
                // visibly "lifts" off the page. Folder icon + prominent title.
                <div className="flex items-center gap-2 px-3 py-2 bg-paper-tint-cream border border-rule-line-strong border-b-0 rounded-t-card shadow-card">
                  <Sprite name="folder" size={14} className="text-accent shrink-0" />
                  <span className="font-display text-[14px] font-semibold text-ink-primary truncate">
                    {row.group.title}
                  </span>
                  <span className="font-mono text-[10px] uppercase tracking-wider text-ink-muted shrink-0">
                    · {row.group.variant_count} variants
                  </span>
                </div>
              ) : row.kind === 'variant' ? (
                // Variant row inside a multi-variant group: shares the
                // tinted container with siblings, accent rule on the left,
                // closes with a bottom border + rounded corner on the last.
                <div
                  className={clsx(
                    'px-3 py-1 bg-paper-tint-cream border-x border-rule-line-strong relative',
                    row.variantPos === 'last' && 'border-b rounded-b-card pb-2 shadow-card',
                  )}
                >
                  <span
                    aria-hidden
                    className="absolute left-0 top-0 bottom-0 w-0.5 bg-accent-soft"
                  />
                  <SongStrip
                    project={row.project!}
                    onOpen={() => onOpen(row.project!)}
                    onLaunch={onLaunch}
                  />
                </div>
              ) : (
                // Single-variant project — explicitly "outside any group."
                // Wrap in a subtle 1px ring so the standalone reads as a
                // self-contained card, even when the SongStrip's own border
                // is too quiet against neighboring tinted group containers.
                <div className="ring-1 ring-rule-line-strong/40 rounded-card">
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
