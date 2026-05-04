// Auto-aligned with /public/raw/icons/{doodles,field}/.
// To add: drop a PNG in the right folder and add its name here.

export const DOODLE_SPRITES = [
  'bookmark',
  'cassette-spool',
  'cassette-tape',
  'checkmark',
  'chevron-right',
  'cloud',
  'dancer',
  'drum-kit',
  'drumstick',
  'folder',
  'house',
  'magnifying-glass',
  'metronome',
  'microphone',
  'moon',
  'paper-airplane',
  'paperclip',
  'pencil-stub',
  'piano-keyboard',
  'plus',
  'rainstorm-cloud',
  'scissors',
  'star',
  'x-mark',
] as const;

export const FIELD_SPRITES = ['bpm', 'key', 'length', 'time-sig', 'tracks'] as const;

export type DoodleName = (typeof DOODLE_SPRITES)[number];
export type FieldName = (typeof FIELD_SPRITES)[number];
export type SpriteName = DoodleName | FieldName;

export function spritePath(name: SpriteName): string {
  if ((FIELD_SPRITES as readonly string[]).includes(name)) {
    return `/raw/icons/field/${name}.png`;
  }
  return `/raw/icons/doodles/${name}.png`;
}
