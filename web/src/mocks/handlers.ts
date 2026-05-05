import projectsJson from './projects.json';
import type {
  HomeResponse,
  JournalBatch,
  ProjectDetail,
  ProjectSummary,
  Proposal,
  ProposalSubmission,
  ProposedAction,
  Shelf,
} from '../lib/types';
import type { ListProjectsParams } from '../lib/api';

const projectsState: ProjectSummary[] = (
  JSON.parse(JSON.stringify(projectsJson)) as ProjectSummary[]
).map((p) => {
  // Seed a small mix of "broken" states so the UI surfacing has data to render
  // in mocks mode. id=2 → missing samples; id=3 → parse failed; everyone else
  // is healthy. Stable per-id so screenshots and tests are deterministic.
  const missingSampleCount = p.id === 2 ? 3 : 0;
  const parseStatus: 'ok' | 'failed' = p.id === 3 ? 'failed' : 'ok';
  const parseError = parseStatus === 'failed'
    ? 'GzipError: not a valid Ableton project file'
    : null;
  return {
    ...p,
    // Real backend uses Ableton palette 0..13; the legacy fixture used 1..14.
    color_tag: p.color_tag === null ? null : Math.max(0, Math.min(13, p.color_tag - 1)),
    effort_score: ((p.id * 17) % 100),
    effort_breakdown: null,
    missing_sample_count: missingSampleCount,
    parse_status: parseStatus,
    parse_error: parseError,
  };
});

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

function _orderProjects(rows: ProjectSummary[], params: ListProjectsParams): ProjectSummary[] {
  const dir = params.order_dir === 'asc' ? 1 : -1;
  const by = params.order_by;
  if (!by) return rows;
  const sorted = [...rows].sort((a, b) => {
    let av: number | string;
    let bv: number | string;
    if (by === 'name') {
      av = a.name;
      bv = b.name;
    } else if (by === 'effort') {
      av = a.effort_score ?? -1;
      bv = b.effort_score ?? -1;
    } else {
      av = a.last_modified;
      bv = b.last_modified;
    }
    if (av < bv) return -1 * dir;
    if (av > bv) return 1 * dir;
    return 0;
  });
  return sorted;
}

function _filterProjects(rows: ProjectSummary[], params: ListProjectsParams): ProjectSummary[] {
  return rows.filter((p) => {
    if (params.query) {
      const q = params.query.toLowerCase();
      if (!p.name.toLowerCase().includes(q) && !p.parent_dir.toLowerCase().includes(q)) {
        return false;
      }
    }
    if (params.tempo_min !== undefined && (p.tempo == null || p.tempo < params.tempo_min)) return false;
    if (params.tempo_max !== undefined && (p.tempo == null || p.tempo > params.tempo_max)) return false;
    if (params.archived !== undefined && Boolean(p.is_archived) !== params.archived) return false;
    if (params.min_effort !== undefined && (p.effort_score == null || p.effort_score < params.min_effort)) return false;
    if (params.max_effort !== undefined && (p.effort_score == null || p.effort_score > params.max_effort)) return false;
    if (params.broken !== undefined) {
      const isBroken = p.parse_status === 'failed' || (p.missing_sample_count ?? 0) > 0;
      if (params.broken && !isBroken) return false;
      if (!params.broken && isBroken) return false;
    }
    return true;
  });
}

const SIX_MONTHS = 60 * 60 * 24 * 30 * 6;
const TWO_YEARS = 60 * 60 * 24 * 365 * 2;

function _shelvesFor(rows: ProjectSummary[]): Shelf[] {
  const now = Math.floor(Date.now() / 1000);
  // currently-working: blue color tag (10) OR modified within 6 months
  const currentlyWorking = rows.filter(
    (p) => p.color_tag === 10 || (now - p.last_modified) < SIX_MONTHS,
  );
  // forgotten-gems: high effort (>=60) and old mtime (>= 2y)
  const forgottenGems = rows.filter(
    (p) => (p.effort_score ?? 0) >= 60 && (now - p.last_modified) >= TWO_YEARS,
  );
  // almost-done: orange/yellow color tags (palette indices 1,2,3 – warm)
  const almostDone = rows.filter((p) => p.color_tag != null && [1, 2, 3].includes(p.color_tag));
  // has-potential: purple color tag (12)
  const hasPotential = rows.filter((p) => p.color_tag === 12);
  // untriaged: no color tag
  const untriaged = rows.filter((p) => p.color_tag == null);
  // broken: parse failed OR has missing samples
  const broken = rows.filter(
    (p) => p.parse_status === 'failed' || (p.missing_sample_count ?? 0) > 0,
  );

  return [
    {
      id: 'currently-working',
      title: 'Currently working',
      description: 'Recent edits and active sketches.',
      see_all_query: 'order_by=mtime&order_dir=desc',
      projects: currentlyWorking.slice(0, 12),
    },
    {
      id: 'forgotten-gems',
      title: 'Forgotten gems',
      description: 'High-effort projects that have been quiet for a while.',
      see_all_query: 'min_effort=60&order_by=mtime&order_dir=asc',
      projects: forgottenGems.slice(0, 12),
    },
    {
      id: 'almost-done',
      title: 'Almost done',
      description: 'Tagged warm — close to the finish line.',
      see_all_query: 'order_by=mtime&order_dir=desc',
      projects: almostDone.slice(0, 12),
    },
    {
      id: 'has-potential',
      title: 'Has potential',
      description: 'Marked for revisit.',
      see_all_query: 'order_by=mtime&order_dir=desc',
      projects: hasPotential.slice(0, 12),
    },
    {
      id: 'untriaged',
      title: 'Untriaged',
      description: 'No color tag yet — needs a glance.',
      see_all_query: 'order_by=mtime&order_dir=desc',
      projects: untriaged.slice(0, 12),
    },
    {
      id: 'broken',
      title: 'Broken',
      description: 'Failed to parse or referencing missing samples.',
      see_all_query: 'broken=true&order_by=mtime&order_dir=desc',
      projects: broken.slice(0, 20),
    },
  ];
}

export const mock = {
  listProjects(params: ListProjectsParams = {}): ProjectSummary[] {
    const filtered = _filterProjects(projectsState, params);
    const ordered = _orderProjects(filtered, params);
    if (params.limit !== undefined) return ordered.slice(0, params.limit);
    return ordered;
  },
  getHome(): HomeResponse {
    return { shelves: _shelvesFor(projectsState) };
  },
  getCategory(id: string): ProjectSummary[] {
    // Mock falls back to whatever the home shelf already returns. The real
    // backend endpoint returns the full uncapped set, but for offline UI dev
    // the home sample is plenty.
    const shelf = _shelvesFor(projectsState).find((s) => s.id === id);
    return shelf ? shelf.projects : [];
  },
  openProject(_id: number): { ok: true } {
    return { ok: true };
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
