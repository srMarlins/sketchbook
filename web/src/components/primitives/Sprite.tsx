import { AccessibleIcon } from '@radix-ui/react-accessible-icon';
import clsx from 'clsx';
import { type SpriteName, spritePath } from './sprite-names';

export interface SpriteProps {
  name: SpriteName;
  /** Pixel size for both axes; sprites are square. */
  size?: number;
  /** When set, the icon is exposed to AT with this label. Otherwise aria-hidden. */
  label?: string;
  className?: string;
}

export function Sprite({ name, size = 24, label, className }: SpriteProps) {
  const img = (
    <img
      src={spritePath(name)}
      width={size}
      height={size}
      alt=""
      aria-hidden={label ? undefined : true}
      draggable={false}
      className={clsx('inline-block select-none', className)}
    />
  );

  if (label) {
    return <AccessibleIcon label={label}>{img}</AccessibleIcon>;
  }
  return img;
}
