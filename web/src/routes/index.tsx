import { useNavigate } from '@tanstack/react-router';
import { BrandingHeader } from '../components/surface/BrandingHeader';
import { Desk } from '../components/surface/Desk';
import { Sidebar } from '../components/surface/Sidebar';
import { HomeShelf } from '../components/surface/HomeShelf';
import { ProjectCard } from '../components/data/ProjectCard';
import { SearchBar } from '../components/inputs/SearchBar';
import { LoadingState } from '../components/feedback/LoadingState';
import { ErrorState } from '../components/feedback/ErrorState';
import { EmptyState } from '../components/feedback/EmptyState';
import { useHome, useProposals } from '../app/queries';
import type { HomeResponse } from '../lib/types';

export function HomeRoute() {
  const home = useHome();
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
      {home.isLoading ? <LoadingState label="loading shelves…" /> : null}
      {home.isError ? (
        <ErrorState body={String(home.error)} onRetry={() => home.refetch()} />
      ) : null}
      {home.data ? <HomeShelves home={home.data} /> : null}
    </Desk>
  );
}

function HomeShelves({ home }: { home: HomeResponse }) {
  const navigate = useNavigate();
  const totalProjects = home.shelves.reduce((acc, s) => acc + s.projects.length, 0);

  if (totalProjects === 0) {
    return <EmptyState title="no projects" body="run `audio-cli scan` to populate the catalog" />;
  }

  return (
    <div className="space-y-8">
      {home.shelves.map((shelf) => {
        const seeAllProps = shelf.see_all_query
          ? { seeAllHref: `/?${shelf.see_all_query}` }
          : {};
        return (
        <HomeShelf
          key={shelf.id}
          title={shelf.title}
          description={shelf.description}
          {...seeAllProps}
        >
          {shelf.projects.map((p) => (
            <ProjectCard
              key={p.id}
              project={p}
              onOpen={(id) => void navigate({ to: '/n/$notebookId', params: { notebookId: String(id) } })}
            />
          ))}
        </HomeShelf>
        );
      })}
    </div>
  );
}
