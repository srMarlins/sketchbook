import { useNavigate } from '@tanstack/react-router';
import { useState } from 'react';
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
import { useProposals } from '../app/queries';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { approveProposal, rejectProposal } from '../lib/api';

export function ProposalsRoute() {
  const navigate = useNavigate();
  const proposals = useProposals();
  const qc = useQueryClient();
  const [pendingIds, setPendingIds] = useState<Set<string>>(new Set());

  const approve = useMutation({
    mutationFn: approveProposal,
    onMutate: (id) => setPendingIds((s) => new Set(s).add(id)),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['proposals'] }),
  });
  const reject = useMutation({
    mutationFn: rejectProposal,
    onMutate: (id) => setPendingIds((s) => new Set(s).add(id)),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['proposals'] }),
  });

  const sidebarItems = [
    { id: 'home', label: 'Home', icon: 'house' as const },
    { id: 'projects', label: 'Projects', icon: 'folder' as const },
    {
      id: 'proposals',
      label: 'Proposals',
      icon: 'paper-airplane' as const,
      badge: proposals.data?.filter((p) => p.status === 'pending' && !pendingIds.has(p.id)).length || null,
    },
    { id: 'claude', label: 'Claude', icon: 'cassette-tape' as const },
  ];

  const pending = (proposals.data ?? []).filter(
    (p) => p.status === 'pending' && !pendingIds.has(p.id),
  );

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
          <div className="flex items-baseline gap-3">
            <h2 className="font-display text-3xl">Proposals</h2>
            <span className="font-mono text-sm text-ink-muted">{pending.length} pending</span>
            {pending.length > 0 ? (
              <div className="ml-auto flex gap-2">
                <Button
                  variant="primary"
                  size="sm"
                  onClick={() => pending.forEach((p) => approve.mutate(p.id))}
                >
                  approve all
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => pending.forEach((p) => reject.mutate(p.id))}
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
            body="try `claude propose ...` in the CLI to queue suggestions."
          />
        ) : null}
        {pending.length > 0 ? (
          <TornPagePile>
            {pending.map((p) => (
              <ProposalCard
                key={p.id}
                proposal={p}
                onApprove={(id) => approve.mutate(id)}
                onReject={(id) => reject.mutate(id)}
              />
            ))}
          </TornPagePile>
        ) : null}
      </NotebookPage>
    </Desk>
  );
}
