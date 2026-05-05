import { useState } from 'react';
import { useNavigate } from '@tanstack/react-router';
import { BrandingHeader } from '../components/surface/BrandingHeader';
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

const KIND_TO_SPINE: Record<string, SpineKind> = {
  manila: 'tinted-cream',
  kraft: 'tinted-rose',
  lined: 'plain',
};

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
      branding={<BrandingHeader />}
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

  return (
    <Shelf title={`${projects.length} projects across ${notebooks.length} notebooks`}>
      {notebooks.map((nb) => {
        const lastUpdated = fmtDate(nb.lastUpdated);
        return (
          <NotebookSpine
            key={nb.id}
            id={nb.id}
            title={nb.title}
            kind={KIND_TO_SPINE[nb.kind] ?? 'plain'}
            count={nb.count}
            {...(lastUpdated ? { lastUpdated } : {})}
            onOpen={(id) => navigate({ to: '/n/$notebookId', params: { notebookId: id } })}
          />
        );
      })}
    </Shelf>
  );
}
