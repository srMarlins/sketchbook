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

/**
 * Renders an icon as a CSS mask so the silhouette takes `currentColor`. This
 * lets `text-*` utilities tint the icon, which raster `<img>` cannot do — the
 * sketch PNGs are dark on transparent and become invisible on dark surfaces.
 */
export function Sprite({ name, size = 24, label, className }: SpriteProps) {
  const url = `url(${JSON.stringify(spritePath(name))})`;
  const span = (
    <span
      role={label ? 'img' : undefined}
      aria-hidden={label ? undefined : true}
      className={clsx('inline-block shrink-0 align-[-0.125em] bg-current', className)}
      style={{
        width: size,
        height: size,
        WebkitMaskImage: url,
        maskImage: url,
        WebkitMaskRepeat: 'no-repeat',
        maskRepeat: 'no-repeat',
        WebkitMaskPosition: 'center',
        maskPosition: 'center',
        WebkitMaskSize: 'contain',
        maskSize: 'contain',
      }}
    />
  );

  if (label) {
    return <AccessibleIcon label={label}>{span}</AccessibleIcon>;
  }
  return span;
}
