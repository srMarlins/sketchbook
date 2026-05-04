import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { ProposalCard } from './ProposalCard';
import type { Proposal } from '../../lib/types';

const fakeProposal = (overrides: Partial<Proposal> = {}): Proposal => ({
  id: 'p-1',
  project_id: 1,
  verb: 'rename',
  target: '/x/foo.als',
  diff: { before: 'foo', after: 'foo (rev)' },
  reason: 'Filename has trailing whitespace.',
  source: 'claude-cli',
  created_at: 1700000000,
  status: 'pending',
  ...overrides,
});

describe('<ProposalCard />', () => {
  test('shows verb, target, and diff', () => {
    render(<ProposalCard proposal={fakeProposal()} />);
    expect(screen.getByText('rename')).toBeInTheDocument();
    expect(screen.getByText('/x/foo.als')).toBeInTheDocument();
    expect(screen.getByText('foo')).toBeInTheDocument();
    expect(screen.getByText('foo (rev)')).toBeInTheDocument();
  });

  test('approve and reject fire correct handlers', async () => {
    const onApprove = vi.fn();
    const onReject = vi.fn();
    render(<ProposalCard proposal={fakeProposal()} onApprove={onApprove} onReject={onReject} />);
    await userEvent.click(screen.getByRole('button', { name: 'approve' }));
    await userEvent.click(screen.getByRole('button', { name: 'reject' }));
    expect(onApprove).toHaveBeenCalledWith('p-1');
    expect(onReject).toHaveBeenCalledWith('p-1');
  });

  test('rotation is deterministic per id', () => {
    const a = render(<ProposalCard proposal={fakeProposal({ id: 'p-deterministic' })} />);
    const rotA = (a.container.querySelector('[data-testid="proposal-card"]') as HTMLElement).style.transform;
    a.unmount();
    const b = render(<ProposalCard proposal={fakeProposal({ id: 'p-deterministic' })} />);
    const rotB = (b.container.querySelector('[data-testid="proposal-card"]') as HTMLElement).style.transform;
    expect(rotB).toBe(rotA);
  });
});
