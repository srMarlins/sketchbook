import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { SongStrip } from './SongStrip';
import type { Project } from '../../lib/types';

function fakeProject(overrides: Partial<Project> = {}): Project {
  return {
    id: 42,
    path: '/x/foo.als',
    name: 'lazy ridge',
    parent_dir: '/x',
    tempo: 120,
    time_sig_num: 4,
    time_sig_den: 4,
    key: 'Cmaj',
    track_count: 12,
    audio_tracks: 8,
    midi_tracks: 3,
    return_tracks: 1,
    length_seconds: 245,
    live_version: '12.0.10',
    last_modified: 1700000000,
    last_scanned: 1700003600,
    file_hash: 'abc123',
    is_archived: 0,
    color_tag: 5,
    notes: null,
    tags: [],
    ...overrides,
  };
}

describe('<SongStrip />', () => {
  test('renders all 14 als colors without crashing', () => {
    for (let i = 1; i <= 14; i++) {
      const { unmount } = render(<SongStrip project={fakeProject({ id: i, color_tag: i })} />);
      expect(screen.getByText('lazy ridge')).toBeInTheDocument();
      unmount();
    }
  });

  test('hold method is deterministic per project id', () => {
    const project = fakeProject();
    const a = render(<SongStrip project={project} />);
    const holdA = a.container.querySelector('[data-hold]')?.getAttribute('data-hold');
    a.unmount();
    const b = render(<SongStrip project={project} />);
    const holdB = b.container.querySelector('[data-hold]')?.getAttribute('data-hold');
    expect(holdB).toBe(holdA);
  });

  test('click fires onOpen with project id', async () => {
    const fn = vi.fn();
    render(<SongStrip project={fakeProject({ id: 99 })} onOpen={fn} />);
    await userEvent.click(screen.getByRole('button'));
    expect(fn).toHaveBeenCalledWith(99);
  });

  test('renders BPM, key, tracks, length fields', () => {
    render(<SongStrip project={fakeProject()} />);
    expect(screen.getByText('120.0')).toBeInTheDocument();
    expect(screen.getByText('Cmaj')).toBeInTheDocument();
    expect(screen.getByText('4/4')).toBeInTheDocument();
    expect(screen.getByText('12')).toBeInTheDocument();
    expect(screen.getByText('4:05')).toBeInTheDocument();
  });
});
