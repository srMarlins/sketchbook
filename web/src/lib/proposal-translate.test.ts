import { describe, expect, test } from 'vitest';
import type { ProjectSummary } from './types';
import {
  translateJournal,
  translateProposalHead,
  translateProposed,
} from './proposal-translate';

const fakeProject: ProjectSummary = {
  id: 7,
  path: '/x/demos.als',
  name: 'demos',
  parent_dir: '/x',
  tempo: 124,
  time_sig_num: 4,
  time_sig_den: 4,
  track_count: 8,
  audio_tracks: 4,
  midi_tracks: 4,
  return_tracks: 0,
  length_seconds: 240,
  live_version: '11.2.10',
  last_modified: 0,
  last_scanned: 0,
  file_hash: 'h',
  is_archived: 0,
  color_tag: 2,
  notes: null,
  tags: ['vox', 'rough'],
};

describe('translateProposed', () => {
  test('RenameProject reads before from project, after from args', () => {
    const t = translateProposed(
      { type: 'RenameProject', args: { project_id: 7, new_dir_name: 'demos (rev)' } },
      fakeProject,
    );
    expect(t.verb).toBe('rename');
    expect(t.before).toBe('demos');
    expect(t.after).toBe('demos (rev)');
  });

  test('SetColorTag formats as als-<n+1>', () => {
    const t = translateProposed(
      { type: 'SetColorTag', args: { project_id: 7, color: 5 } },
      fakeProject,
    );
    expect(t.before).toBe('als-3');
    expect(t.after).toBe('als-6');
  });

  test('SetTags joins lists', () => {
    const t = translateProposed(
      { type: 'SetTags', args: { project_id: 7, tags: ['vox', 'demo', 'master'] } },
      fakeProject,
    );
    expect(t.before).toBe('vox, rough');
    expect(t.after).toBe('vox, demo, master');
  });
});

describe('translateJournal', () => {
  test('RenameProject pulls from journal payload, no project lookup', () => {
    const t = translateJournal({
      type: 'RenameProject',
      project_id: 7,
      from_: '/x/old',
      to: '/x/new',
      hash_before: 'h',
    });
    expect(t.before).toBe('/x/old');
    expect(t.after).toBe('/x/new');
  });
});

describe('translateProposalHead', () => {
  test('reports elided actions count', () => {
    const t = translateProposalHead(
      {
        proposal_id: 'p',
        actor: 'claude',
        actions: [
          { type: 'SetTags', args: { project_id: 7, tags: ['vox'] } },
          { type: 'SetColorTag', args: { project_id: 7, color: 2 } },
        ],
        rationale: null,
      },
      fakeProject,
    );
    expect(t.verb).toBe('tag');
    expect(t.extra).toBe(1);
  });
});
