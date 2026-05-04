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

export function HomeRoute() {
  const projects = useProjects();
  const proposals = useProposals();
  const navigate = useNavigate();

  const sidebarItems = [
    { id: 'home', label: 'Home', icon: 'house' as const },
    { id: 'projects', label: 'Projects', icon: 'folder' as const },
    {
      id: 'proposals',
      label: 'Proposals',
      icon: 'paper-airplane' as const,
      badge: proposals.data?.filter((p) => p.status === 'pending').length || null,
    },
    { id: 'claude', label: 'Claude', icon: 'cassette-tape' as const },
    { id: 'archive', label: 'Archive', icon: 'bookmark' as const },
    { id: 'tags', label: 'Tags', icon: 'star' as const },
    { id: 'help', label: 'Help', icon: 'magnifying-glass' as const },
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
      {projects.isLoading ? <LoadingState label="loading sketches…" /> : null}
      {projects.isError ? <ErrorState body={String(projects.error)} onRetry={() => projects.refetch()} /> : null}
      {projects.data ? <ShelfBody projects={projects.data} /> : null}
    </Desk>
  );
}

function ShelfBody({ projects }: { projects: Parameters<typeof deriveNotebooks>[0] }) {
  const navigate = useNavigate();
  const notebooks = deriveNotebooks(projects);

  if (notebooks.length === 0) {
    return <EmptyState title="no notebooks" body="run claude scan to populate" />;
  }

  return (
    <div className="space-y-4">
      <Shelf>
        {notebooks.map((nb) => (
          <NotebookSpine
            key={nb.id}
            id={nb.id}
            title={nb.title}
            kind={nb.kind}
            count={nb.count}
            {...(fmtDate(nb.lastUpdated) ? { lastUpdated: fmtDate(nb.lastUpdated)! } : {})}
            onOpen={(id) => navigate({ to: '/n/$notebookId', params: { notebookId: id } })}
          />
        ))}
      </Shelf>
    </div>
  );
}
