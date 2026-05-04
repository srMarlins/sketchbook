import projectsJson from './projects.json';
import type {
  JournalBatch,
  ProjectDetail,
  ProjectSummary,
  Proposal,
  ProposalSubmission,
  ProposedAction,
} from '../lib/types';

const projectsState: ProjectSummary[] = (
  JSON.parse(JSON.stringify(projectsJson)) as ProjectSummary[]
).map((p) => ({
  ...p,
  // Real backend uses Ableton palette 0..13; the legacy fixture used 1..14.
  color_tag: p.color_tag === null ? null : Math.max(0, Math.min(13, p.color_tag - 1)),
}));

function _detailFor(p: ProjectSummary): ProjectDetail {
  const tagSeed = p.id;
  return {
    ...p,
    plugins: tagSeed % 3 === 0
      ? []
      : [
          { plugin_name: 'Pro-Q 3', plugin_type: 'vst3', track_name: 'Master' },
          { plugin_name: 'Serum', plugin_type: 'vst3', track_name: 'Lead' },
        ],
    samples: tagSeed % 4 === 0
      ? []
      : [
          { sample_path: `${p.parent_dir}/Samples/kick.wav`, sample_hash: 'abc', is_missing: 0 },
          { sample_path: `${p.parent_dir}/Samples/snare.wav`, sample_hash: 'def', is_missing: 0 },
        ],
  };
}

function _seedProposals(): Proposal[] {
  const targets = projectsState.slice(0, 6);
  return targets.map((p, i) => {
    const actions: ProposedAction[] =
      i % 3 === 0
        ? [{ type: 'SetTags', args: { project_id: p.id, tags: [...p.tags, 'review'] } }]
        : i % 3 === 1
          ? [{ type: 'RenameProject', args: { project_id: p.id, new_dir_name: `${p.name} (rev)` } }]
          : [{ type: 'ArchiveProject', args: { project_id: p.id } }];
    return {
      proposal_id: `mock-${i + 1}`,
      actor: 'claude',
      actions,
      rationale: 'Heuristic match on filename + last-modified gap.',
    };
  });
}

function _seedJournal(): JournalBatch[] {
  const targets = projectsState.slice(0, 4);
  return targets.map((p, i) => ({
    batch_id: `mock-batch-${i + 1}`,
    actor: i % 2 === 0 ? 'claude' : 'user',
    actions: [
      i % 2 === 0
        ? {
            type: 'SetColorTag' as const,
            project_id: p.id,
            before: null,
            after: (p.id % 14) - 1 < 0 ? 0 : ((p.id % 14) - 1),
          }
        : {
            type: 'RenameProject' as const,
            project_id: p.id,
            from_: p.parent_dir,
            to: `${p.parent_dir.replace(/\/[^/]+$/, '')}/${p.name} (rev)`,
            hash_before: p.file_hash,
          },
    ],
  }));
}

const proposalsState: Proposal[] = _seedProposals();
const journalState: JournalBatch[] = _seedJournal();

export const mock = {
  listProjects(): ProjectSummary[] {
    return projectsState;
  },
  getProject(id: number): ProjectDetail | undefined {
    const p = projectsState.find((x) => x.id === id);
    return p ? _detailFor(p) : undefined;
  },
  listProposals(): Proposal[] {
    return proposalsState;
  },
  getProposal(id: string): Proposal | undefined {
    return proposalsState.find((p) => p.proposal_id === id);
  },
  submitProposal(body: ProposalSubmission): { proposal_id: string } {
    const id = `mock-${Date.now()}`;
    proposalsState.push({
      proposal_id: id,
      actor: body.actor ?? 'claude',
      actions: body.actions,
      rationale: body.rationale ?? null,
    });
    return { proposal_id: id };
  },
  approveProposal(id: string): { batch_id: string } {
    const idx = proposalsState.findIndex((p) => p.proposal_id === id);
    if (idx < 0) throw new Error(`Proposal ${id} not found`);
    proposalsState.splice(idx, 1);
    const batchId = `mock-batch-${Date.now()}`;
    journalState.unshift({
      batch_id: batchId,
      actor: 'claude',
      actions: [],
    });
    return { batch_id: batchId };
  },
  rejectProposal(id: string): void {
    const idx = proposalsState.findIndex((p) => p.proposal_id === id);
    if (idx < 0) throw new Error(`Proposal ${id} not found`);
    proposalsState.splice(idx, 1);
  },
  listJournal(): JournalBatch[] {
    return journalState;
  },
  undoBatch(batchId: string): { undone: string } {
    const idx = journalState.findIndex((b) => b.batch_id === batchId);
    if (idx < 0) throw new Error(`Batch ${batchId} not found`);
    journalState.splice(idx, 1);
    return { undone: batchId };
  },
};
