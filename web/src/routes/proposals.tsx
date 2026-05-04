import { useNavigate } from '@tanstack/react-router';
import { useMemo } from 'react';
import { BrandingHeader } from '../components/surface/BrandingHeader';
import { Desk } from '../components/surface/Desk';
import { Sidebar } from '../components/surface/Sidebar';
import { NotebookPage } from '../components/surface/NotebookPage';
import { TornPagePile } from '../components/surface/TornPagePile';
import { ProposalCard } from '../components/data/ProposalCard';
import { Button } from '../components/inputs/Button';
import { SearchBar } from '../components/inputs/SearchBar';
import { EmptyState } from '../components/feedback/EmptyState';
import { LoadingState } from '../components/feedback/LoadingState';
import { ErrorState } from '../components/feedback/ErrorState';
import {
  useApproveProposal,
  useProjects,
  useProposals,
  useRejectProposal,
} from '../app/queries';

export function ProposalsRoute() {
  const navigate = useNavigate();
  const proposals = useProposals();
  const projects = useProjects();
  const approve = useApproveProposal();
  const reject = useRejectProposal();

  const projectsById = useMemo(
    () => new Map((projects.data ?? []).map((p) => [p.id, p] as const)),
    [projects.data],
  );

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

  const pending = proposals.data ?? [];

  return (
    <Desk
      branding={<BrandingHeader />}
      search={<SearchBar />}
      sidebar={
        <Sidebar
          activeId="proposals"
          items={sidebarItems}
          onActivate={(id) => {
            if (id === 'home') void navigate({ to: '/' });
            if (id === 'claude') void navigate({ to: '/n/$notebookId', params: { notebookId: 'claude' } });
          }}
        />
      }
    >
      <NotebookPage
        header={
          <div className="flex items-baseline gap-3 flex-wrap">
            <h2 className="text-xl font-semibold tracking-tight">Proposals</h2>
            <span className="font-mono text-xs text-ink-muted">
              {pending.length} pending
            </span>
            {pending.length > 0 ? (
              <div className="ml-auto flex gap-2">
                <Button
                  variant="primary"
                  size="sm"
                  onClick={() => pending.forEach((p) => approve.mutate(p.proposal_id))}
                >
                  approve all
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => pending.forEach((p) => reject.mutate(p.proposal_id))}
                >
                  reject all
                </Button>
              </div>
            ) : null}
          </div>
        }
      >
        {proposals.isLoading ? <LoadingState label="loading proposals…" /> : null}
        {proposals.isError ? (
          <ErrorState body={String(proposals.error)} onRetry={() => proposals.refetch()} />
        ) : null}
        {proposals.data && pending.length === 0 ? (
          <EmptyState
            icon="cloud"
            title="nothing pending"
            body="run `audio-mcp propose_batch ...` from Claude or the CLI to queue suggestions."
          />
        ) : null}
        {pending.length > 0 ? (
          <TornPagePile>
            {pending.map((p) => {
              const head = p.actions[0];
              const project = head ? projectsById.get(head.args.project_id) : undefined;
              return (
                <ProposalCard
                  key={p.proposal_id}
                  proposal={p}
                  {...(project ? { project } : {})}
                  onApprove={(id) => approve.mutate(id)}
                  onReject={(id) => reject.mutate(id)}
                />
              );
            })}
          </TornPagePile>
        ) : null}
      </NotebookPage>
    </Desk>
  );
}
