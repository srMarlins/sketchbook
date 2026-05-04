import { Sprite } from '../components/primitives/Sprite';
import { DOODLE_SPRITES, FIELD_SPRITES, type SpriteName } from '../components/primitives/sprite-names';
import { Button, type ButtonVariant } from '../components/inputs/Button';
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
];
