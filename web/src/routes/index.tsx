import { useState } from 'react';
import { useNavigate } from '@tanstack/react-router';
import { BrandingHeader } from '../components/surface/BrandingHeader';
import { IndexerStatus } from '../app/IndexerStatus';
import { Desk } from '../components/surface/Desk';
import { Sidebar } from '../components/surface/Sidebar';
import { Shelf } from '../components/surface/Shelf';
import { NotebookSpine } from '../components/surface/NotebookSpine';
import { HighlightsStrip, type HighlightShelfId } from '../components/data/HighlightsStrip';
import { SongStrip } from '../components/data/SongStrip';
import { ProjectCorkboard } from '../components/corkboard';
import { SearchBar } from '../components/inputs/SearchBar';
import { LoadingState } from '../components/feedback/LoadingState';
import { ErrorState } from '../components/feedback/ErrorState';
import { EmptyState } from '../components/feedback/EmptyState';
import { useHome, useOpenProject, useProjects, useProposals } from '../app/queries';
import { deriveNotebooks, fmtDate } from '../app/notebooks';
import { deriveProjectGroups } from '../lib/project-groups';
import type { ProjectSummary, Shelf as HomeShelf } from '../lib/types';
import type { NotebookKind as SpineKind } from '../components/surface/NotebookSpine';

const STRIP_LIMIT = 8;

// Map deriveNotebooks() kinds to spine visual variants. Year notebooks (kind
// 'lined') cycle through tints so adjacent years are visually distinct.
const LINED_TINT_CYCLE: SpineKind[] = ['tinted-blue', 'tinted-sage', 'tinted-cream'];

// Tab strip colors per notebook id-prefix — small accent across the top of
// each spine so different sections don't all read identical at a glance.
// Uses CSS custom properties so the colors stay correct across light/dark.
function spineFor(
  id: string,
  kind: 'manila' | 'kraft' | 'lined',
  index: number,
): { spineKind: SpineKind; tabColor: string | null } {
  if (id === 'inbox') return { spineKind: 'tinted-cream', tabColor: 'var(--accent)' };
  if (id === 'archive') return { spineKind: 'aged', tabColor: null };
  if (id === 'claude') return { spineKind: 'tinted-rose', tabColor: 'var(--als-13)' };
  if (kind === 'lined') {
    // Years and tags cycle through tints; each gets a distinct als-color tab.
    const tints: SpineKind[] = LINED_TINT_CYCLE;
    const tabColors = ['var(--als-10)', 'var(--als-6)', 'var(--als-3)']; // blue / green / mustard
    return {
      spineKind: tints[index % tints.length]!,
      tabColor: tabColors[index % tabColors.length]!,
    };
  }
  return { spineKind: 'tinted-cream', tabColor: null };
}

export function HomeRoute() {
  const projects = useProjects();
  const proposals = useProposals();
  const home = useHome();
  const openMut = useOpenProject();
  const navigate = useNavigate();
  const [openProjectId, setOpenProjectId] = useState<number | null>(null);
  const [corkOpen, setCorkOpen] = useState(false);

  const sidebarItems = [
    { id: 'home', label: 'Home', icon: 'house' as const },
    { id: 'projects', label: 'Projects', icon: 'folder' as const },
    {
      id: 'proposals',
      label: 'Proposals',
      icon: 'paper-airplane' as const,
      badge: proposals.data?.length || null,
    },
    { id: 'claude', label: 'Claude', icon: 'cassette-tape' as const },
  ];

  return (
    <Desk
      branding={
        <div className="flex items-center gap-3">
          <BrandingHeader />
          <IndexerStatus />
        </div>
      }
      search={<SearchBar />}
      sidebar={
        <Sidebar
          activeId="home"
          items={sidebarItems}
          onActivate={(id) => {
            if (id === 'proposals') void navigate({ to: '/proposals' });
            if (id === 'claude') void navigate({ to: '/n/$notebookId', params: { notebookId: 'claude' } });
          }}
        />
      }
    >
      <h1 className="sr-only">Audio catalog</h1>
      <HighlightsRow
        shelves={home.data?.shelves ?? []}
        loading={home.isLoading}
        error={home.isError}
        onSelect={(id) =>
          void navigate({ to: '/n/$notebookId', params: { notebookId: `cat-${id}` } })
        }
      />
      {projects.isLoading ? <LoadingState label="loading projects…" /> : null}
      {projects.isError ? (
        <ErrorState body={String(projects.error)} onRetry={() => projects.refetch()} />
      ) : null}
      {projects.data ? <NotebooksGrid projects={projects.data} /> : null}
      {home.data ? (
        <div className="mt-10 grid grid-cols-1 gap-8 lg:grid-cols-2">
          <ShelfStrip
            shelf={home.data.shelves.find((s) => s.id === 'recent-activity')}
            seeAllTo="cat-currently-working"
            onOpenDetail={(p) => {
              setOpenProjectId(p.id);
              setCorkOpen(true);
            }}
            onLaunch={(id) => openMut.mutate(id)}
            onSeeAll={(target) =>
              void navigate({ to: '/n/$notebookId', params: { notebookId: target } })
            }
          />
          <ShelfStrip
            shelf={home.data.shelves.find((s) => s.id === 'gems-sample')}
            seeAllTo="cat-forgotten-gems"
            onOpenDetail={(p) => {
              setOpenProjectId(p.id);
              setCorkOpen(true);
            }}
            onLaunch={(id) => openMut.mutate(id)}
            onSeeAll={(target) =>
              void navigate({ to: '/n/$notebookId', params: { notebookId: target } })
            }
          />
        </div>
      ) : null}
      <ProjectCorkboard projectId={openProjectId} open={corkOpen} onOpenChange={setCorkOpen} />
    </Desk>
  );
}

function ShelfStrip({
  shelf,
  seeAllTo,
  onOpenDetail,
  onLaunch,
  onSeeAll,
}: {
  shelf: HomeShelf | undefined;
  seeAllTo: string;
  onOpenDetail: (project: ProjectSummary) => void;
  onLaunch: (projectId: number) => void;
  onSeeAll: (target: string) => void;
}) {
  if (!shelf) return null;
  // Group raw rows by parent_dir, then take one representative per group up to STRIP_LIMIT.
  const groups = deriveProjectGroups(shelf.projects).slice(0, STRIP_LIMIT);
  return (
    <section className="space-y-2">
      <header className="flex items-baseline justify-between gap-3 px-1">
        <div>
          <h2 className="font-display text-base text-ink-primary">{shelf.title}</h2>
          <p className="text-xs text-ink-muted">{shelf.description}</p>
        </div>
        <button
          type="button"
          onClick={() => onSeeAll(seeAllTo)}
          className="font-mono text-[11px] text-ink-muted uppercase tracking-wide hover:text-ink-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent rounded-chip px-1.5 py-0.5"
        >
          see all →
        </button>
      </header>
      {groups.length === 0 ? (
        <p className="px-1 text-xs italic text-ink-muted">Nothing here yet.</p>
      ) : (
        <div className="space-y-1.5">
          {groups.map((g) => (
            <SongStrip
              key={g.id}
              project={g.representative}
              onOpen={() => onOpenDetail(g.representative)}
              onLaunch={onLaunch}
            />
          ))}
        </div>
      )}
    </section>
  );
}

function HighlightsRow({
  shelves,
  loading,
  error,
  onSelect,
}: {
  shelves: HomeShelf[];
  loading: boolean;
  error: boolean;
  onSelect: (id: HighlightShelfId) => void;
}) {
  if (error) return null;
  if (loading) {
    const placeholders: HomeShelf[] = (
      ['currently-working', 'forgotten-gems', 'almost-done', 'has-potential', 'untriaged'] as const
    ).map((id) => ({
      id,
      title: id.replace(/-/g, ' '),
      description: '',
      see_all_query: '',
      projects: [],
    }));
    return (
      <div className="mb-6 opacity-70" aria-busy>
        <HighlightsStrip shelves={placeholders} onSelect={onSelect} />
      </div>
    );
  }
  return (
    <div className="mb-6">
      <HighlightsStrip shelves={shelves} onSelect={onSelect} />
    </div>
  );
}

function NotebooksGrid({ projects }: { projects: ProjectSummary[] }) {
  const navigate = useNavigate();
  const notebooks = deriveNotebooks(projects);

  if (notebooks.length === 0) {
    return <EmptyState title="no projects" body="run `audio-cli scan` to populate the catalog" />;
  }

  // Track index per kind so the cycling tints/tabs in spineFor stay stable
  // even as inbox/archive/claude break the natural ordering.
  const linedIndices = new Map<string, number>();
  let linedCounter = 0;
  for (const nb of notebooks) {
    if (nb.kind === 'lined') {
      linedIndices.set(nb.id, linedCounter);
      linedCounter += 1;
    }
  }

  return (
    <Shelf title={`${projects.length} projects across ${notebooks.length} notebooks`}>
      {notebooks.map((nb) => {
        const lastUpdated = fmtDate(nb.lastUpdated);
        const { spineKind, tabColor } = spineFor(nb.id, nb.kind, linedIndices.get(nb.id) ?? 0);
        return (
          <NotebookSpine
            key={nb.id}
            id={nb.id}
            title={nb.title}
            kind={spineKind}
            tabColor={tabColor}
            count={nb.count}
            {...(lastUpdated ? { lastUpdated } : {})}
            onOpen={(id) => navigate({ to: '/n/$notebookId', params: { notebookId: id } })}
          />
        );
      })}
    </Shelf>
  );
}
