import clsx from 'clsx';
import { useMemo } from 'react';
import { mulberry32, seedFromString } from '../../lib/seed';
import { Button } from '../inputs/Button';
import type { Proposal } from '../../lib/types';

export interface ProposalCardProps {
  proposal: Proposal;
  onApprove?: (id: string) => void;
  onReject?: (id: string) => void;
}

export function ProposalCard({ proposal, onApprove, onReject }: ProposalCardProps) {
  const rotation = useMemo(() => {
    const r = mulberry32(seedFromString(`prop:${proposal.id}`));
    return (r() * 6 - 3).toFixed(2);
  }, [proposal.id]);

  return (
    <article
      className={clsx(
        'relative bg-surface-strip text-ink-primary',
        'p-4 rounded-sm shadow-page max-w-md',
        'border-t border-rule-line/60',
      )}
      style={{ transform: `rotate(${rotation}deg)` }}
      data-testid="proposal-card"
    >
      <header className="flex flex-wrap items-baseline gap-2">
        <span className="font-display text-2xl text-accent-action capitalize">{proposal.verb}</span>
        <span className="font-mono text-sm text-ink-muted truncate">{proposal.target}</span>
      </header>

      <p className="mt-2 font-sans text-sm text-ink-secondary">{proposal.reason}</p>

      <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 font-mono text-sm">
        <dt className="text-ink-muted">before</dt>
        <dd className="line-through decoration-accent-action/70">{proposal.diff.before || '—'}</dd>
        <dt className="text-ink-muted">after</dt>
        <dd className="text-pin-green font-semibold">{proposal.diff.after}</dd>
      </dl>

      <footer className="mt-4 flex items-center gap-2">
        <Button variant="primary" size="sm" onClick={() => onApprove?.(proposal.id)}>
          approve
        </Button>
        <Button variant="ghost" size="sm" onClick={() => onReject?.(proposal.id)}>
          reject
        </Button>
        <span className="ml-auto font-mono text-xs text-ink-muted">{proposal.source}</span>
      </footer>
    </article>
  );
}
