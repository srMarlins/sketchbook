import clsx from 'clsx';
import { useMemo } from 'react';
import { Sprite } from '../primitives/Sprite';
import {
  ALS_HEX,
  inkHexForStrip,
  TEXT_ON_ALS,
} from '../../theme/contrast-table';
import { seedFromString, mulberry32, seedPick, seedRange } from '../../lib/seed';
import type { Project } from '../../lib/types';

export type HoldMethod = 'washi-top' | 'washi-corners' | 'staple' | 'tape-strip';

const HOLD_METHODS: readonly HoldMethod[] = [
  'washi-top',
  'washi-corners',
  'staple',
  'tape-strip',
];

export interface SongStripProps {
  project: Project;
  onOpen?: (projectId: number) => void;
}

function fmtSeconds(sec: number | null): string {
  if (sec == null) return '—';
  const m = Math.floor(sec / 60);
  const s = Math.round(sec % 60);
  return `${m}:${s.toString().padStart(2, '0')}`;
}

function fmtTimeSig(num: number | null, den: number | null): string {
  if (num == null || den == null) return '—';
  return `${num}/${den}`;
}

export function SongStrip({ project, onOpen }: SongStripProps) {
  const colorIdx = project.color_tag ?? 14;
  const stripBg = ALS_HEX[colorIdx] ?? ALS_HEX[14]!;
  const inkHex = inkHexForStrip(colorIdx);
  const inkClass =
    TEXT_ON_ALS[colorIdx] === 'light'
      ? 'text-[var(--ink-on-strip-light)]'
      : 'text-[var(--ink-on-strip-dark)]';

  const idStr = String(project.id);
  const { hold, rotation } = useMemo(() => {
    const seed = seedFromString(`strip:${idStr}`);
    const r = mulberry32(seed);
    return {
      hold: seedPick(HOLD_METHODS, seed),
      // small per-strip rotation jitter (-1.2..1.2 deg)
      rotation: (r() * 2.4 - 1.2).toFixed(2),
    };
  }, [idStr]);

  const tilt = `rotate(${rotation}deg)`;

  return (
    <button
      type="button"
      onClick={() => onOpen?.(project.id)}
      className={clsx(
        'group relative block w-full text-left rounded-sm transition-transform duration-fast ease-paper',
        'hover:-translate-y-[2px] hover:shadow-lift focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-action',
        inkClass,
      )}
      style={{
        backgroundColor: stripBg,
        color: inkHex,
        boxShadow: 'var(--shadow-pin)',
        transform: tilt,
      }}
      data-hold={hold}
      data-color-idx={colorIdx}
    >
      <span className="flex flex-wrap items-center gap-x-4 gap-y-1 px-4 py-3">
        <span className="font-mono text-base font-semibold truncate min-w-0 flex-1">
          {project.name}
        </span>
        <Field icon="bpm" label={project.tempo != null ? project.tempo.toFixed(1) : '—'} />
        <Field icon="key" label={project.key ?? '—'} />
        <Field icon="time-sig" label={fmtTimeSig(project.time_sig_num, project.time_sig_den)} />
        <Field icon="tracks" label={String(project.track_count)} />
        <Field icon="length" label={fmtSeconds(project.length_seconds)} />
      </span>
      <HoldVisuals hold={hold} colorIdx={colorIdx} />
    </button>
  );
}

function Field({ icon, label }: { icon: 'bpm' | 'key' | 'time-sig' | 'tracks' | 'length'; label: string }) {
  return (
    <span className="inline-flex items-center gap-1 font-mono text-sm whitespace-nowrap">
      <Sprite name={icon} size={14} />
      <span>{label}</span>
    </span>
  );
}

function HoldVisuals({ hold, colorIdx }: { hold: HoldMethod; colorIdx: number }) {
  const seed = seedFromString(`hold:${hold}:${colorIdx}`);
  const r = mulberry32(seed);
  const tilt1 = (r() * 8 - 4).toFixed(1);
  const tilt2 = (r() * 8 - 4).toFixed(1);
  const offset = seedRange(`pos:${hold}:${colorIdx}`, 6, 24).toFixed(1);

  if (hold === 'washi-top') {
    return (
      <span
        aria-hidden
        className="absolute -top-1 left-1/2 h-3 w-12 bg-pin-yellow/70"
        style={{ transform: `translateX(-50%) rotate(${tilt1}deg)` }}
      />
    );
  }
  if (hold === 'washi-corners') {
    return (
      <>
        <span
          aria-hidden
          className="absolute -top-1 -left-1 h-3 w-8 bg-pin-blue/70"
          style={{ transform: `rotate(${tilt1}deg)` }}
        />
        <span
          aria-hidden
          className="absolute -top-1 -right-1 h-3 w-8 bg-pin-blue/70"
          style={{ transform: `rotate(${tilt2}deg)` }}
        />
      </>
    );
  }
  if (hold === 'staple') {
    return (
      <span
        aria-hidden
        className="absolute top-1 h-2 w-3 border-2 border-ink-muted"
        style={{ left: `${offset}px`, transform: `rotate(${tilt1}deg)` }}
      />
    );
  }
  // tape-strip
  return (
    <span
      aria-hidden
      className="absolute -top-0.5 right-3 h-2 w-10 bg-ink-muted/30"
      style={{ transform: `rotate(${tilt1}deg)` }}
    />
  );
}
