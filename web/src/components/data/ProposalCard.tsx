import clsx from 'clsx';
import { translateProposalHead } from '../../lib/proposal-translate';
import type { ProjectSummary, Proposal } from '../../lib/types';
import { Button } from '../inputs/Button';

export interface ProposalCardProps {
  proposal: Proposal;
  /** Optional: pass the project so before-state can be filled in. */
  project?: ProjectSummary;
  onApprove?: (proposalId: string) => void;
  onReject?: (proposalId: string) => void;
}

const VERB_TINT: Record<string, string> = {
  rename: 'bg-paper-tint-blue',
  move: 'bg-paper-tint-blue',
  archive: 'bg-paper-tint-rose',
  color: 'bg-paper-tint-cream',
  tag: 'bg-paper-tint-sage',
};

export function ProposalCard({ proposal, project, onApprove, onReject }: ProposalCardProps) {
  const head = translateProposalHead(proposal, project);

  return (
    <article
      data-testid="proposal-card"
      className={clsx(
        'rounded-card border border-rule-line shadow-card',
        'bg-surface-card',
      )}
    >
      <header
        className={clsx(
          'flex flex-wrap items-baseline gap-x-3 gap-y-1 px-4 py-2 rounded-t-card',
          'border-b border-rule-line',
          VERB_TINT[head.verb] ?? 'bg-surface-sunken',
        )}
      >
        <span className="text-xs font-mono uppercase tracking-wider text-ink-secondary">
          {head.verb}
        </span>
        <span className="text-sm font-medium text-ink-primary truncate min-w-0 flex-1">
          {head.label}
        </span>
        {head.extra > 0 ? (
          <span className="text-[11px] font-mono text-ink-muted">
            +{head.extra} more
          </span>
        ) : null}
        <span className="text-[11px] font-mono text-ink-muted">{proposal.actor}</span>
      </header>

      <div className="px-4 py-3 grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1.5 font-mono text-[12px]">
        <span className="text-ink-muted">before</span>
        <span className="text-ink-secondary line-through decoration-accent/60 truncate">
          {head.before}
        </span>
        <span className="text-ink-muted">after</span>
        <span className="text-accent-positive font-semibold truncate">{head.after}</span>
      </div>

      {proposal.rationale ? (
        <p className="px-4 pb-3 text-[13px] text-ink-secondary">{proposal.rationale}</p>
      ) : null}

      <footer className="flex items-center gap-2 px-4 py-2 border-t border-rule-line bg-surface-sunken/40 rounded-b-card">
        <Button variant="primary" size="sm" onClick={() => onApprove?.(proposal.proposal_id)}>
          approve
        </Button>
        <Button variant="ghost" size="sm" onClick={() => onReject?.(proposal.proposal_id)}>
          reject
        </Button>
        <span className="ml-auto text-[10px] font-mono text-ink-muted truncate">
          {proposal.proposal_id}
        </span>
      </footer>
    </article>
  );
}
