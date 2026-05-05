import { describe, expect, test } from 'vitest';
import { deriveProjectGroups } from './project-groups';
import type { ProjectSummary } from './types';

function p(overrides: Partial<ProjectSummary>): ProjectSummary {
  return {
    id: 0,
    path: '/lib/foo.als',
    name: 'foo',
    parent_dir: '/lib',
    tempo: 120,
    time_sig_num: 4,
    time_sig_den: 4,
    track_count: 4,
    audio_tracks: 2,
    midi_tracks: 1,
    return_tracks: 1,
    length_seconds: 60,
    live_version: '12.0.0',
    last_modified: 1_700_000_000,
    last_scanned: 1_700_000_000,
    file_hash: 'h',
    is_archived: 0,
    color_tag: null,
    notes: null,
    tags: [],
    effort_score: null,
    effort_breakdown: null,
    missing_sample_count: 0,
    parse_status: 'ok',
    parse_error: null,
    ...overrides,
  };
}

describe('deriveProjectGroups', () => {
  test('groups variants by parent_dir', () => {
    const groups = deriveProjectGroups([
      p({ id: 1, parent_dir: '/a', last_modified: 100 }),
      p({ id: 2, parent_dir: '/a', last_modified: 200 }),
      p({ id: 3, parent_dir: '/b', last_modified: 50 }),
    ]);
    expect(groups).toHaveLength(2);
    const a = groups.find((g) => g.parent_dir === '/a')!;
    expect(a.variants).toHaveLength(2);
    expect(a.variant_count).toBe(2);
    const b = groups.find((g) => g.parent_dir === '/b')!;
    expect(b.variant_count).toBe(1);
  });

  test('variants sorted by last_modified desc', () => {
    const [g] = deriveProjectGroups([
      p({ id: 1, parent_dir: '/a', last_modified: 100 }),
      p({ id: 2, parent_dir: '/a', last_modified: 300 }),
      p({ id: 3, parent_dir: '/a', last_modified: 200 }),
    ]);
    expect(g!.variants.map((v) => v.id)).toEqual([2, 3, 1]);
    expect(g!.latest_mtime).toBe(300);
  });

  test('representative is newest non-archived', () => {
    const [g] = deriveProjectGroups([
      p({ id: 1, parent_dir: '/a', last_modified: 300, is_archived: 1 }),
      p({ id: 2, parent_dir: '/a', last_modified: 200, is_archived: 0 }),
      p({ id: 3, parent_dir: '/a', last_modified: 100, is_archived: 0 }),
    ]);
    expect(g!.representative.id).toBe(2);
  });

  test('representative falls back to newest if all archived', () => {
    const [g] = deriveProjectGroups([
      p({ id: 1, parent_dir: '/a', last_modified: 300, is_archived: 1 }),
      p({ id: 2, parent_dir: '/a', last_modified: 200, is_archived: 1 }),
    ]);
    expect(g!.representative.id).toBe(1);
  });

  test('effort_score is the max across variants', () => {
    const [g] = deriveProjectGroups([
      p({ id: 1, parent_dir: '/a', effort_score: 30 }),
      p({ id: 2, parent_dir: '/a', effort_score: 80 }),
      p({ id: 3, parent_dir: '/a', effort_score: null }),
    ]);
    expect(g!.effort_score).toBe(80);
  });

  test('effort_score is null when all variants are null', () => {
    const [g] = deriveProjectGroups([
      p({ id: 1, parent_dir: '/a', effort_score: null }),
      p({ id: 2, parent_dir: '/a', effort_score: null }),
    ]);
    expect(g!.effort_score).toBeNull();
  });

  test('total_tracks comes from representative, not sum', () => {
    const [g] = deriveProjectGroups([
      p({ id: 1, parent_dir: '/a', last_modified: 100, track_count: 4 }),
      p({ id: 2, parent_dir: '/a', last_modified: 200, track_count: 9 }),
    ]);
    expect(g!.total_tracks).toBe(9);
  });

  test('state_color picks loudest by priority (green > yellow > orange > blue > purple > red)', () => {
    const [g1] = deriveProjectGroups([
      p({ id: 1, parent_dir: '/a', color_tag: 1 }), // red
      p({ id: 2, parent_dir: '/a', color_tag: 4 }), // green
      p({ id: 3, parent_dir: '/a', color_tag: 5 }), // blue
    ]);
    expect(g1!.state_color).toBe(4);

    const [g2] = deriveProjectGroups([
      p({ id: 1, parent_dir: '/b', color_tag: 5 }), // blue
      p({ id: 2, parent_dir: '/b', color_tag: 6 }), // purple
      p({ id: 3, parent_dir: '/b', color_tag: 1 }), // red
    ]);
    expect(g2!.state_color).toBe(5); // blue beats purple/red
  });

  test('state_color null when no variant has a color_tag', () => {
    const [g] = deriveProjectGroups([
      p({ id: 1, parent_dir: '/a', color_tag: null }),
      p({ id: 2, parent_dir: '/a', color_tag: null }),
    ]);
    expect(g!.state_color).toBeNull();
  });

  test('state_color falls back to a non-priority color when no priority match', () => {
    const [g] = deriveProjectGroups([
      p({ id: 1, parent_dir: '/a', color_tag: 11 }),
      p({ id: 2, parent_dir: '/a', color_tag: null }),
    ]);
    expect(g!.state_color).toBe(11);
  });

  test('single-variant folder still wraps', () => {
    const [g] = deriveProjectGroups([p({ id: 1, parent_dir: '/solo' })]);
    expect(g!.variant_count).toBe(1);
    expect(g!.variants).toHaveLength(1);
  });

  test('groups sorted by latest_mtime desc', () => {
    const groups = deriveProjectGroups([
      p({ id: 1, parent_dir: '/old', last_modified: 100 }),
      p({ id: 2, parent_dir: '/new', last_modified: 500 }),
      p({ id: 3, parent_dir: '/mid', last_modified: 300 }),
    ]);
    expect(groups.map((g) => g.parent_dir)).toEqual(['/new', '/mid', '/old']);
  });

  test('title strips trailing " Project" and uses basename', () => {
    const [g1] = deriveProjectGroups([
      p({ id: 1, parent_dir: 'Z:/Music/Lazy Ridge Project' }),
    ]);
    expect(g1!.title).toBe('Lazy Ridge');

    const [g2] = deriveProjectGroups([
      p({ id: 2, parent_dir: 'C:\\stuff\\Foo Bar' }),
    ]);
    expect(g2!.title).toBe('Foo Bar');
  });

  test('empty input returns empty array', () => {
    expect(deriveProjectGroups([])).toEqual([]);
  });
});
