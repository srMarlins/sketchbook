import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { SongStrip } from './SongStrip';
import type { ProjectSummary } from '../../lib/types';

function fakeProject(overrides: Partial<ProjectSummary> = {}): ProjectSummary {
  return {
    id: 42,
    path: '/x/foo.als',
    name: 'lazy ridge',
    parent_dir: '/x',
    tempo: 120,
    time_sig_num: 4,
    time_sig_den: 4,
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
  test('renders all 14 als color tags without crashing', () => {
    for (let i = 0; i < 14; i++) {
      const { unmount } = render(<SongStrip project={fakeProject({ id: i + 1, color_tag: i })} />);
      expect(screen.getByText('lazy ridge')).toBeInTheDocument();
      unmount();
    }
  });

  test('null color tag still renders the row', () => {
    render(<SongStrip project={fakeProject({ color_tag: null })} />);
    expect(screen.getByText('lazy ridge')).toBeInTheDocument();
  });

  test('click fires onOpen with project id', async () => {
    const fn = vi.fn();
    render(<SongStrip project={fakeProject({ id: 99 })} onOpen={fn} />);
    await userEvent.click(screen.getByRole('button'));
    expect(fn).toHaveBeenCalledWith(99);
  });

  test('tag chips render up to 3 with overflow indicator', () => {
    render(
      <SongStrip
        project={fakeProject({ tags: ['vox', 'demos', 'beats', 'piano', 'rough'] })}
      />,
    );
    expect(screen.getByText('vox')).toBeInTheDocument();
    expect(screen.getByText('demos')).toBeInTheDocument();
    expect(screen.getByText('beats')).toBeInTheDocument();
    expect(screen.queryByText('piano')).not.toBeInTheDocument();
    expect(screen.getByText('+2')).toBeInTheDocument();
  });
});
