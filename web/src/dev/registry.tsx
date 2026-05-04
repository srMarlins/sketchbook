import { Sprite } from '../components/primitives/Sprite';
import { DOODLE_SPRITES, FIELD_SPRITES, type SpriteName } from '../components/primitives/sprite-names';
import { Button, type ButtonVariant } from '../components/inputs/Button';
import { FilterChip } from '../components/data/FilterChip';
import { SongStrip } from '../components/data/SongStrip';
import projectsJson from '../mocks/projects.json';
import type { Project } from '../lib/types';
import type { DevEntry } from './types';

const sampleProjects = (projectsJson as Project[]).slice(0, 14).map((p, i) => ({
  ...p,
  color_tag: i + 1, // force one of each als color for the showcase
}));

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
      size: {
        type: 'number',
        label: 'size',
        defaultValue: 48,
      },
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
      label: {
        type: 'text',
        label: 'label',
        defaultValue: 'Save',
      },
      disabled: {
        type: 'toggle',
        label: 'disabled',
        defaultValue: false,
      },
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
];
