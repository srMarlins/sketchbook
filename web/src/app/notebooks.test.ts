import { describe, expect, test } from 'vitest';
import { deriveNotebooks } from './notebooks';
import type { Project } from '../lib/types';

const baseProject: Project = {
  id: 1,
  path: '/x/foo.als',
  name: 'foo',
  parent_dir: '/x',
  tempo: 120,
  time_sig_num: 4,
  time_sig_den: 4,
  key: 'C',
  track_count: 1,
  audio_tracks: 1,
  midi_tracks: 0,
  return_tracks: 0,
  length_seconds: 60,
  live_version: '12.0',
  last_modified: 0,
  last_scanned: 0,
  file_hash: 'h',
  is_archived: 0,
  color_tag: 1,
  notes: null,
  tags: [],
};

describe('deriveNotebooks', () => {
  test('produces inbox + per-year + per-tag + archive + claude', () => {
    const projects: Project[] = [
      { ...baseProject, id: 1, parent_dir: '/Projects/2024', tags: ['vox'] },
      { ...baseProject, id: 2, parent_dir: '/Projects/2024', tags: ['vox', 'beats'] },
      { ...baseProject, id: 3, parent_dir: '/Projects/2025', tags: ['ambient'] },
      { ...baseProject, id: 4, parent_dir: '/Projects/2026', tags: [] },
      { ...baseProject, id: 5, parent_dir: '/Projects/Inbox', tags: [] },
      { ...baseProject, id: 6, parent_dir: '/Projects/2024', tags: [], is_archived: 1 },
    ];
    const ids = deriveNotebooks(projects).map((n) => n.id);
    expect(ids).toContain('inbox');
    expect(ids).toContain('year-2024');
    expect(ids).toContain('year-2025');
    expect(ids).toContain('year-2026');
    expect(ids).toContain('tag-vox');
    expect(ids).toContain('tag-beats');
    expect(ids).toContain('tag-ambient');
    expect(ids).toContain('archive');
    expect(ids).toContain('claude');
  });
});
