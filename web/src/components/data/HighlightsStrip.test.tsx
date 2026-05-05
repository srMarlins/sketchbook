import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { HighlightsStrip } from './HighlightsStrip';
import type { ProjectSummary, Shelf } from '../../lib/types';

function fakeProject(id: number): ProjectSummary {
  return {
    id,
    path: `/x/${id}.als`,
    name: `p${id}`,
    parent_dir: '/x',
    tempo: null,
    time_sig_num: null,
    time_sig_den: null,
    track_count: null,
    audio_tracks: null,
    midi_tracks: null,
    return_tracks: null,
    length_seconds: null,
    live_version: null,
    last_modified: 0,
    last_scanned: 0,
    file_hash: '',
    is_archived: 0,
    color_tag: null,
    notes: null,
    tags: [],
    effort_score: null,
    effort_breakdown: null,
    missing_sample_count: 0,
    parse_status: 'ok',
    parse_error: null,
  };
}

function shelf(id: Shelf['id'], n: number, title?: string): Shelf {
  return {
    id,
    title: title ?? id,
    description: '',
    see_all_query: '',
    projects: Array.from({ length: n }, (_, i) => fakeProject(i + 1)),
  };
}

const fullShelves: Shelf[] = [
  shelf('currently-working', 5, 'Currently working'),
  shelf('forgotten-gems', 2, 'Forgotten gems'),
  shelf('almost-done', 3, 'Almost done'),
  shelf('has-potential', 1, 'Has potential'),
  shelf('untriaged', 0, 'Untriaged'),
  shelf('broken', 4, 'Broken'),
];

describe('<HighlightsStrip />', () => {
  test('renders 6 chips in fixed order', () => {
    render(<HighlightsStrip shelves={fullShelves} onSelect={() => {}} />);
    const chips = screen.getAllByRole('button');
    expect(chips).toHaveLength(6);
    expect(chips[0]).toHaveAttribute('data-testid', 'highlight-chip-currently-working');
    expect(chips[1]).toHaveAttribute('data-testid', 'highlight-chip-forgotten-gems');
    expect(chips[2]).toHaveAttribute('data-testid', 'highlight-chip-almost-done');
    expect(chips[3]).toHaveAttribute('data-testid', 'highlight-chip-has-potential');
    expect(chips[4]).toHaveAttribute('data-testid', 'highlight-chip-untriaged');
    expect(chips[5]).toHaveAttribute('data-testid', 'highlight-chip-broken');
  });

  test('counts match shelf project lengths', () => {
    render(<HighlightsStrip shelves={fullShelves} onSelect={() => {}} />);
    expect(screen.getByTestId('highlight-chip-currently-working').textContent).toMatch(/· 5/);
    expect(screen.getByTestId('highlight-chip-forgotten-gems').textContent).toMatch(/· 2/);
    expect(screen.getByTestId('highlight-chip-almost-done').textContent).toMatch(/· 3/);
    expect(screen.getByTestId('highlight-chip-has-potential').textContent).toMatch(/· 1/);
    expect(screen.getByTestId('highlight-chip-untriaged').textContent).toMatch(/· 0/);
    expect(screen.getByTestId('highlight-chip-broken').textContent).toMatch(/· 4/);
  });

  test('zero-count chip is dim (data-empty + opacity class) but still rendered', () => {
    render(<HighlightsStrip shelves={fullShelves} onSelect={() => {}} />);
    const empty = screen.getByTestId('highlight-chip-untriaged');
    expect(empty).toHaveAttribute('data-empty', 'true');
    expect(empty.className).toMatch(/opacity-50/);

    const filled = screen.getByTestId('highlight-chip-currently-working');
    expect(filled).not.toHaveAttribute('data-empty');
  });

  test('onSelect called with shelf id on click', async () => {
    const fn = vi.fn();
    render(<HighlightsStrip shelves={fullShelves} onSelect={fn} />);
    await userEvent.click(screen.getByTestId('highlight-chip-forgotten-gems'));
    expect(fn).toHaveBeenCalledWith('forgotten-gems');
  });

  test('missing shelf renders chip with count 0 using fallback title', () => {
    render(<HighlightsStrip shelves={[shelf('currently-working', 3, 'Currently working')]} onSelect={() => {}} />);
    const empty = screen.getByTestId('highlight-chip-forgotten-gems');
    expect(empty).toHaveAttribute('data-empty', 'true');
    expect(empty.textContent).toMatch(/Forgotten gems/);
    expect(empty.textContent).toMatch(/· 0/);
  });
});
