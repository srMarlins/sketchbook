import { useNavigate } from '@tanstack/react-router';
import { useMemo, useState } from 'react';
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
  const projects = useProjects({ limit: 1000 });
  const approve = useApproveProposal();
  const reject = useRejectProposal();
  // Per-card busy tracking. We track ids that have an in-flight mutation so
  // double-clicks (and any remaining concurrent triggers) are no-ops.
  const [busyIds, setBusyIds] = useState<Set<string>>(new Set());
  const [bulkRunning, setBulkRunning] = useState(false);

  const projectsById = useMemo(
    () => new Map((projects.data ?? []).map((p) => [p.id, p] as const)),
    [projects.data],
  );

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

  const pending = proposals.data ?? [];

  const markBusy = (id: string, busy: boolean) => {
    setBusyIds((prev) => {
      const next = new Set(prev);
      if (busy) next.add(id);
      else next.delete(id);
      return next;
    });
  };

  const approveOne = async (id: string) => {
    if (busyIds.has(id)) return;
    markBusy(id, true);
    try {
      await approve.mutateAsync(id);
    } catch {
      // Error surfaces via React Query; nothing to do here. Failed approves
      // leave the proposal in place server-side, so the user can retry.
    } finally {
      markBusy(id, false);
    }
  };

  const rejectOne = async (id: string) => {
    if (busyIds.has(id)) return;
    markBusy(id, true);
    try {
      await reject.mutateAsync(id);
    } catch {
      /* same */
    } finally {
      markBusy(id, false);
    }
  };

  const approveAll = async () => {
    if (bulkRunning) return;
    setBulkRunning(true);
    try {
      // Sequential, not Promise.all — server has a per-proposal lock but the
      // whole-batch transaction model is simpler to reason about serially,
      // and bulk-approving 50 proposals at once is not a UX win.
      for (const p of pending) {
        await approveOne(p.proposal_id);
      }
    } finally {
      setBulkRunning(false);
    }
  };

  const rejectAll = async () => {
    if (bulkRunning) return;
    setBulkRunning(true);
    try {
      for (const p of pending) {
        await rejectOne(p.proposal_id);
      }
    } finally {
      setBulkRunning(false);
    }
  };

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
                <Button variant="primary" size="sm" disabled={bulkRunning} onClick={approveAll}>
                  {bulkRunning ? 'approving…' : 'approve all'}
                </Button>
                <Button variant="ghost" size="sm" disabled={bulkRunning} onClick={rejectAll}>
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
                  busy={busyIds.has(p.proposal_id)}
                  onApprove={(id) => void approveOne(id)}
                  onReject={(id) => void rejectOne(id)}
                />
              );
            })}
          </TornPagePile>
        ) : null}
      </NotebookPage>
    </Desk>
  );
}
