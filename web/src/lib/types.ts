// Mirrors the FastAPI backend in `packages/web/audio_web/`.
// Field names match the JSON returned by the API exactly — do not snake/camel
// at this boundary; rename only at the component layer if needed.

export interface ProjectSummary {
  id: number;
  path: string;
  name: string;
  parent_dir: string;
  tempo: number | null;
  time_sig_num: number | null;
  time_sig_den: number | null;
  track_count: number | null;
  audio_tracks: number | null;
  midi_tracks: number | null;
  return_tracks: number | null;
  length_seconds: number | null;
  live_version: string | null;
  last_modified: number;
  last_scanned: number;
  file_hash: string;
  is_archived: 0 | 1;
  /** Ableton palette index 0..13, or null. */
  color_tag: number | null;
  notes: string | null;
  tags: string[];
  /** Derived 0..100 effort score, or null if not computed. */
  effort_score: number | null;
  /** Optional human-readable breakdown of how the effort score was derived. */
  effort_breakdown: string | null;
  /** Count of samples on this project whose paths don't exist on disk. */
  missing_sample_count: number | null;
  /** 'ok' if the .als parsed cleanly, 'failed' if scan threw, null for legacy rows. */
  parse_status: 'ok' | 'failed' | null;
  /** Exception message captured when parse_status='failed', else null. */
  parse_error: string | null;
}

// --- home / shelves ----------------------------------------------------------

export interface Shelf {
  id: string;
  title: string;
  description: string;
  see_all_query: string;
  projects: ProjectSummary[];
}

export interface HomeResponse {
  shelves: Shelf[];
}

export interface PluginRow {
  plugin_name: string;
  plugin_type: string | null;
  track_name: string | null;
}

export interface SampleRow {
  sample_path: string;
  sample_hash: string | null;
  is_missing: 0 | 1;
}

export interface ProjectDetail extends ProjectSummary {
  plugins: PluginRow[];
  samples: SampleRow[];
}

// --- repair ----------------------------------------------------------------

export interface MacImportFinding {
  projectId: number;
  path: string;
  name: string;
  parentDir: string;
  macPathsCount: number;
  projectInfoMissing: boolean;
}

export interface SampleCandidate {
  path: string;
  filename: string;
  sizeBytes: number;
}

export interface MissingSampleFinding {
  projectId: number;
  projectPath: string;
  projectName: string;
  missingPath: string;
  autoMatch: SampleCandidate | null;
  candidates: SampleCandidate[];
}

export interface RepairFindings {
  macImports: MacImportFinding[];
  missingSamples: MissingSampleFinding[];
}

// --- proposals ---------------------------------------------------------------

export type ActionType =
  | 'RenameProject'
  | 'MoveProject'
  | 'ArchiveProject'
  | 'SetColorTag'
  | 'SetTags'
  | 'RepairMacPaths'
  | 'RelinkMissingSamples';

export interface RepairMacPathsArgs {
  project_id: number;
}

export interface RelinkSpec {
  old: string;
  new: string;
}

export interface RelinkMissingSamplesArgs {
  project_id: number;
  relinks: RelinkSpec[];
}

export interface RenameProjectArgs {
  project_id: number;
  new_dir_name: string;
}
export interface MoveProjectArgs {
  project_id: number;
  new_parent: string;
}
export interface ArchiveProjectArgs {
  project_id: number;
}
export interface SetColorTagArgs {
  project_id: number;
  /** 0..13 or null to clear. */
  color: number | null;
}
export interface SetTagsArgs {
  project_id: number;
  tags: string[];
}

export type ProposedAction =
  | { type: 'RenameProject'; args: RenameProjectArgs }
  | { type: 'MoveProject'; args: MoveProjectArgs }
  | { type: 'ArchiveProject'; args: ArchiveProjectArgs }
  | { type: 'SetColorTag'; args: SetColorTagArgs }
  | { type: 'SetTags'; args: SetTagsArgs }
  | { type: 'RepairMacPaths'; args: RepairMacPathsArgs }
  | { type: 'RelinkMissingSamples'; args: RelinkMissingSamplesArgs };

export type Actor = 'claude' | 'user';

export interface Proposal {
  proposal_id: string;
  actor: Actor;
  actions: ProposedAction[];
  rationale: string | null;
}

export interface ProposalSubmission {
  actor?: Actor;
  actions: ProposedAction[];
  rationale?: string | null;
}

// --- journal -----------------------------------------------------------------

export interface JournalActionRename {
  type: 'RenameProject';
  project_id: number;
  from_: string;
  to: string;
  hash_before: string;
}
export interface JournalActionMove {
  type: 'MoveProject';
  project_id: number;
  from_: string;
  to: string;
}
export interface JournalActionArchive {
  type: 'ArchiveProject';
  project_id: number;
  from_: string;
  to: string;
}
export interface JournalActionColor {
  type: 'SetColorTag';
  project_id: number;
  before: number | null;
  after: number | null;
}
export interface JournalActionTags {
  type: 'SetTags';
  project_id: number;
  before: string[];
  after: string[];
}

export type JournalAction =
  | JournalActionRename
  | JournalActionMove
  | JournalActionArchive
  | JournalActionColor
  | JournalActionTags;

export interface JournalBatch {
  batch_id: string;
  actor: string;
  actions: JournalAction[];
}
