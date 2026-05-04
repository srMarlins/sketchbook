import { Sprite } from '../components/primitives/Sprite';
import { DOODLE_SPRITES, FIELD_SPRITES, type SpriteName } from '../components/primitives/sprite-names';
import type { DevEntry } from './types';

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
];
