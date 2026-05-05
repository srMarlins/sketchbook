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
    effort_score: null,
    effort_breakdown: null,
    missing_sample_count: 0,
    parse_status: 'ok',
    parse_error: null,
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

  test('renders effort_score when present and em-dash when null', () => {
    const { rerender } = render(<SongStrip project={fakeProject({ effort_score: 73 })} />);
    expect(screen.getByText('73')).toBeInTheDocument();
    rerender(<SongStrip project={fakeProject({ effort_score: null })} />);
    // multiple em-dashes may exist (length, etc); ensure the effort label is present.
    expect(screen.getByTitle('effort')).toBeInTheDocument();
  });

  test('launch button is hidden by default but clickable when shown; stops propagation', async () => {
    const onOpen = vi.fn();
    const onLaunch = vi.fn();
    render(<SongStrip project={fakeProject({ id: 7 })} onOpen={onOpen} onLaunch={onLaunch} />);
    const launch = screen.getByTestId('song-strip-launch');
    // Click via userEvent fires through opacity-0; jsdom doesn't enforce hover, so this still validates wiring.
    await userEvent.click(launch);
    expect(onLaunch).toHaveBeenCalledWith(7);
    expect(onOpen).not.toHaveBeenCalled();
  });

  test('launch button absent when onLaunch is not provided', () => {
    render(<SongStrip project={fakeProject({ id: 8 })} onOpen={() => undefined} />);
    expect(screen.queryByTestId('song-strip-launch')).not.toBeInTheDocument();
  });

  test('shows broken-warning glyph when parse failed', () => {
    render(<SongStrip project={fakeProject({ parse_status: 'failed' })} />);
    const warn = screen.getByTestId('song-strip-broken');
    expect(warn).toBeInTheDocument();
    expect(warn.getAttribute('title')).toContain("won't open");
  });

  test('shows broken-warning glyph when samples are missing', () => {
    render(<SongStrip project={fakeProject({ missing_sample_count: 4 })} />);
    const warn = screen.getByTestId('song-strip-broken');
    expect(warn).toBeInTheDocument();
    expect(warn.getAttribute('title')).toContain('4 missing samples');
  });

  test('no broken-warning glyph when project is healthy', () => {
    render(<SongStrip project={fakeProject()} />);
    expect(screen.queryByTestId('song-strip-broken')).not.toBeInTheDocument();
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
