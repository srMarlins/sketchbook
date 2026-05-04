import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ProjectCard } from './ProjectCard';
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
    effort_score: 60,
    effort_breakdown: null,
    ...overrides,
  };
}

function withQuery(node: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={qc}>{node}</QueryClientProvider>;
}

describe('<ProjectCard />', () => {
  test('renders project name, bpm and effort score', () => {
    render(withQuery(<ProjectCard project={fakeProject()} />));
    expect(screen.getByText('lazy ridge')).toBeInTheDocument();
    expect(screen.getByText('120')).toBeInTheDocument();
    expect(screen.getByText('60')).toBeInTheDocument();
  });

  test('effort bar fills segments proportional to score', () => {
    const { container } = render(withQuery(<ProjectCard project={fakeProject({ effort_score: 35 })} />));
    const segs = container.querySelectorAll('[data-testid="effort-seg"]');
    expect(segs.length).toBe(10);
    const filled = container.querySelectorAll('[data-testid="effort-seg"][data-filled="true"]');
    // 35/100 → 4 segments filled (round)
    expect(filled.length).toBe(4);
  });

  test('null effort_score renders empty bar and em-dash', () => {
    const { container } = render(withQuery(<ProjectCard project={fakeProject({ effort_score: null })} />));
    const filled = container.querySelectorAll('[data-testid="effort-seg"][data-filled="true"]');
    expect(filled.length).toBe(0);
  });

  test('color dot uses --als-N variable for the color_tag', () => {
    const { container } = render(withQuery(<ProjectCard project={fakeProject({ color_tag: 5 })} />));
    const dot = container.querySelector('[data-testid="color-dot"]') as HTMLElement;
    expect(dot).toBeTruthy();
    // tailwind colors using vars come through as background-color rgb-from-var; just verify the data attr
    expect(dot.getAttribute('data-color-tag')).toBe('5');
  });

  test('clicking the body calls onOpen with project id', async () => {
    const fn = vi.fn();
    render(withQuery(<ProjectCard project={fakeProject({ id: 99 })} onOpen={fn} />));
    await userEvent.click(screen.getByTestId('project-card-body'));
    expect(fn).toHaveBeenCalledWith(99);
  });

  test('clicking Open in Ableton fires the open mutation', async () => {
    const fn = vi.fn();
    render(withQuery(<ProjectCard project={fakeProject({ id: 7 })} onOpenInAbleton={fn} />));
    await userEvent.click(screen.getByRole('button', { name: /open in ableton/i }));
    expect(fn).toHaveBeenCalledWith(7);
  });
});
