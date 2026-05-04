import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { ProposalCard } from './ProposalCard';
import type { ProjectSummary, Proposal } from '../../lib/types';

const fakeProject: ProjectSummary = {
  id: 1,
  path: '/x/foo.als',
  name: 'foo',
  parent_dir: '/x',
  tempo: 120,
  time_sig_num: 4,
  time_sig_den: 4,
  track_count: 4,
  audio_tracks: 2,
  midi_tracks: 2,
  return_tracks: 0,
  length_seconds: 100,
  live_version: '12.0',
  last_modified: 0,
  last_scanned: 0,
  file_hash: 'h',
  is_archived: 0,
  color_tag: 0,
  notes: null,
  tags: ['vox'],
  effort_score: null,
  effort_breakdown: null,
};

const renameProposal = (overrides: Partial<Proposal> = {}): Proposal => ({
  proposal_id: 'p-1',
  actor: 'claude',
  actions: [{ type: 'RenameProject', args: { project_id: 1, new_dir_name: 'foo (rev)' } }],
  rationale: 'Filename has trailing whitespace.',
  ...overrides,
});

describe('<ProposalCard />', () => {
  test('shows verb, label, and translated diff', () => {
    render(<ProposalCard proposal={renameProposal()} project={fakeProject} />);
    expect(screen.getByText('rename')).toBeInTheDocument();
    expect(screen.getAllByText('foo (rev)').length).toBeGreaterThan(0);
    expect(screen.getByText('foo')).toBeInTheDocument();
  });

  test('approve and reject fire correct handlers with proposal_id', async () => {
    const onApprove = vi.fn();
    const onReject = vi.fn();
    render(
      <ProposalCard
        proposal={renameProposal()}
        project={fakeProject}
        onApprove={onApprove}
        onReject={onReject}
      />,
    );
    await userEvent.click(screen.getByRole('button', { name: 'approve' }));
    await userEvent.click(screen.getByRole('button', { name: 'reject' }));
    expect(onApprove).toHaveBeenCalledWith('p-1');
    expect(onReject).toHaveBeenCalledWith('p-1');
  });

  test('multi-action proposal shows +N more', () => {
    render(
      <ProposalCard
        proposal={renameProposal({
          actions: [
            { type: 'RenameProject', args: { project_id: 1, new_dir_name: 'foo (rev)' } },
            { type: 'SetTags', args: { project_id: 1, tags: ['vox', 'rough'] } },
          ],
        })}
        project={fakeProject}
      />,
    );
    expect(screen.getByText('+1 more')).toBeInTheDocument();
  });

  test('rationale renders when set', () => {
    render(<ProposalCard proposal={renameProposal()} project={fakeProject} />);
    expect(screen.getByText('Filename has trailing whitespace.')).toBeInTheDocument();
  });

  test('rationale is hidden when null', () => {
    render(
      <ProposalCard
        proposal={renameProposal({ rationale: null })}
        project={fakeProject}
      />,
    );
    expect(screen.queryByText('Filename has trailing whitespace.')).not.toBeInTheDocument();
  });
});
