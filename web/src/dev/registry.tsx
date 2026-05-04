import { Sprite } from '../components/primitives/Sprite';
import { DOODLE_SPRITES, FIELD_SPRITES, type SpriteName } from '../components/primitives/sprite-names';
import { Button, type ButtonVariant } from '../components/inputs/Button';
import { TextInput } from '../components/inputs/TextInput';
import { SearchBar } from '../components/inputs/SearchBar';
import { FilterChip } from '../components/data/FilterChip';
import { SongStrip } from '../components/data/SongStrip';
import { NavStrip } from '../components/data/NavStrip';
import { MarginStickyNote } from '../components/data/MarginStickyNote';
import { ProposalCard } from '../components/data/ProposalCard';
import { EmptyState } from '../components/feedback/EmptyState';
import { ErrorState } from '../components/feedback/ErrorState';
import { LoadingState } from '../components/feedback/LoadingState';
import { Toast, ToastProvider, ToastViewport } from '../components/feedback/Toast';
import projectsJson from '../mocks/projects.json';
import proposalsJson from '../mocks/proposals.json';
import { useState } from 'react';
import type { Project, Proposal } from '../lib/types';
import type { DevEntry } from './types';

const sampleProjects = (projectsJson as Project[]).slice(0, 14).map((p, i) => ({
  ...p,
  color_tag: i + 1,
}));

const sampleProposal = (proposalsJson as Proposal[])[0]!;

function ToastDemo() {
  const [open, setOpen] = useState(false);
  return (
    <ToastProvider>
      <div className="space-y-3">
        <Button onClick={() => setOpen(true)}>fire toast</Button>
        <Toast open={open} onOpenChange={setOpen} title="Saved" description="2 changes applied" tone="success" />
        <ToastViewport />
      </div>
    </ToastProvider>
  );
}

export const registry: DevEntry[] = [
  {
    id: 'sprite',
    group: 'primitives',
    label: 'Sprite',
    controls: {
      name: {
        type: 'select',
        label: 'name',
        defaultValue: 'metronome' as SpriteName,
        options: [...DOODLE_SPRITES, ...FIELD_SPRITES] as readonly SpriteName[],
      },
      size: { type: 'number', label: 'size', defaultValue: 48 },
    },
    render: (c) => (
      <Sprite name={(c['name'] as SpriteName) ?? 'metronome'} size={(c['size'] as number) ?? 48} />
    ),
  },

  {
    id: 'button',
    group: 'inputs',
    label: 'Button',
    controls: {
      variant: {
        type: 'select',
        label: 'variant',
        defaultValue: 'primary' as ButtonVariant,
        options: ['primary', 'secondary', 'ghost'] as ButtonVariant[],
      },
      label: { type: 'text', label: 'label', defaultValue: 'Save' },
      disabled: { type: 'toggle', label: 'disabled', defaultValue: false },
    },
    render: (c) => (
      <div className="flex flex-wrap gap-3">
        <Button variant={c['variant'] as ButtonVariant} disabled={c['disabled'] as boolean}>
          {String(c['label'] ?? 'Save')}
        </Button>
        <Button variant={c['variant'] as ButtonVariant} size="sm" disabled={c['disabled'] as boolean}>
          small
        </Button>
      </div>
    ),
  },
  {
    id: 'text-input',
    group: 'inputs',
    label: 'TextInput',
    render: () => (
      <div className="max-w-sm space-y-4">
        <TextInput label="Project name" placeholder="lazy ridge" hint="must be unique" />
        <TextInput label="Tempo" defaultValue="120" />
        <TextInput label="Project name" defaultValue="" invalid hint="cannot be empty" />
      </div>
    ),
  },
  {
    id: 'search-bar',
    group: 'inputs',
    label: 'SearchBar',
    render: () => (
      <div className="max-w-md">
        <SearchBar />
        <p className="mt-2 text-xs text-ink-muted">try ⌘K, /, esc</p>
      </div>
    ),
  },

  {
    id: 'filter-chip',
    group: 'data',
    label: 'FilterChip',
    render: () => (
      <div className="flex flex-wrap gap-2">
        <FilterChip label="tempo" value="120 BPM" icon="bpm" onDismiss={() => undefined} />
        <FilterChip label="key" value="Cmin" icon="key" onDismiss={() => undefined} />
        <FilterChip label="archived" icon="folder" onDismiss={() => undefined} />
        <FilterChip label="vox" icon="microphone" />
      </div>
    ),
  },
  {
    id: 'song-strip',
    group: 'data',
    label: 'SongStrip',
    render: () => (
      <div className="space-y-3">
        {sampleProjects.map((p) => (
          <SongStrip key={p.id} project={p} onOpen={() => undefined} />
        ))}
      </div>
    ),
  },
  {
    id: 'nav-strip',
    group: 'data',
    label: 'NavStrip',
    render: () => (
      <div className="max-w-xs space-y-2">
        <NavStrip label="Home" icon="house" />
        <NavStrip label="Projects" icon="folder" active />
        <NavStrip label="Proposals" icon="paper-airplane" badge={3} />
        <NavStrip label="Claude" icon="cassette-tape" />
      </div>
    ),
  },
  {
    id: 'margin-sticky-note',
    group: 'data',
    label: 'MarginStickyNote',
    render: () => (
      <div className="flex gap-6 flex-wrap">
        <MarginStickyNote id="n-1" text="rename for clarity?" onOpenSuggestion={() => undefined} />
        <MarginStickyNote id="n-2" text="archive — not opened in 18mo" onOpenSuggestion={() => undefined} />
        <MarginStickyNote id="n-3" text="tag as draft?" onOpenSuggestion={() => undefined} />
      </div>
    ),
  },
  {
    id: 'proposal-card',
    group: 'data',
    label: 'ProposalCard',
    render: () => (
      <ProposalCard
        proposal={sampleProposal}
        onApprove={() => undefined}
        onReject={() => undefined}
      />
    ),
  },

  {
    id: 'empty-state',
    group: 'feedback',
    label: 'EmptyState',
    render: () => <EmptyState title="no sketches yet" body="run claude scan to populate" icon="cloud" />,
  },
  {
    id: 'error-state',
    group: 'feedback',
    label: 'ErrorState',
    render: () => (
      <ErrorState body="500 from /api/projects" onRetry={() => undefined} />
    ),
  },
  {
    id: 'loading-state',
    group: 'feedback',
    label: 'LoadingState',
    render: () => <LoadingState label="finding sketches…" />,
  },
  {
    id: 'toast',
    group: 'feedback',
    label: 'Toast',
    render: () => <ToastDemo />,
  },
];
