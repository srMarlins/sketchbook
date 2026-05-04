import { useNavigate } from '@tanstack/react-router';
import { BrandingHeader } from '../components/surface/BrandingHeader';
import { Desk } from '../components/surface/Desk';
import { Sidebar } from '../components/surface/Sidebar';
import { Shelf } from '../components/surface/Shelf';
import { NotebookSpine } from '../components/surface/NotebookSpine';
import { SearchBar } from '../components/inputs/SearchBar';
import { LoadingState } from '../components/feedback/LoadingState';
import { ErrorState } from '../components/feedback/ErrorState';
import { EmptyState } from '../components/feedback/EmptyState';
import { useProjects, useProposals } from '../app/queries';
import { deriveNotebooks, fmtDate } from '../app/notebooks';
import type { ProjectSummary } from '../lib/types';
import type { NotebookKind as SpineKind } from '../components/surface/NotebookSpine';

const KIND_TO_SPINE: Record<string, SpineKind> = {
  manila: 'tinted-cream',
  kraft: 'tinted-rose',
  lined: 'plain',
};

export function HomeRoute() {
  const projects = useProjects({ limit: 1000 });
  const proposals = useProposals();
  const navigate = useNavigate();

  const sidebarItems = [
    { id: 'home', label: 'Home', icon: 'house' as const },
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
      {projects.isLoading ? <LoadingState label="loading projects…" /> : null}
      {projects.isError ? (
        <ErrorState body={String(projects.error)} onRetry={() => projects.refetch()} />
      ) : null}
      {projects.data ? <NotebooksGrid projects={projects.data} /> : null}
    </Desk>
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
