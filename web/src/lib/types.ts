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

// --- proposals ---------------------------------------------------------------

export type ActionType =
  | 'RenameProject'
  | 'MoveProject'
  | 'ArchiveProject'
  | 'SetColorTag'
  | 'SetTags';

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
  | { type: 'SetTags'; args: SetTagsArgs };

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
