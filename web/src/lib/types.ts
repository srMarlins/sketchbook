export interface Project {
  id: number;
  path: string;
  name: string;
  parent_dir: string;
  tempo: number | null;
  time_sig_num: number | null;
  time_sig_den: number | null;
  key: string | null;
  track_count: number;
  audio_tracks: number;
  midi_tracks: number;
  return_tracks: number;
  length_seconds: number | null;
  live_version: string | null;
  last_modified: number; // unix seconds
  last_scanned: number;
  file_hash: string;
  is_archived: 0 | 1;
  color_tag: number | null; // 1..14 or null
  notes: string | null;
  tags: string[];
}

export interface ProposalDiff {
  before: string;
  after: string;
}

export type ProposalStatus = 'pending' | 'approved' | 'rejected';

export interface Proposal {
  id: string;
  project_id: number | null;
  verb: 'rename' | 'tag' | 'archive' | 'unarchive' | 'note' | 'move';
  target: string;
  diff: ProposalDiff;
  reason: string;
  source: 'user' | 'claude-cli' | 'claude-mcp';
  created_at: number;
  status: ProposalStatus;
}

export type JournalSource = 'user' | 'claude-cli' | 'claude-mcp';

export interface JournalEntry {
  id: string;
  project_id: number | null;
  verb: string;
  target: string;
  source: JournalSource;
  created_at: number;
}

export interface Suggestion {
  project_id: number;
  text: string; // short — fits on a sticky note
  proposed_verb: Proposal['verb'];
  proposed_target: string;
}
