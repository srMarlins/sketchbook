import clsx from 'clsx';
import { Sprite } from '../primitives/Sprite';
import type { ProjectSummary } from '../../lib/types';

export interface SongStripProps {
  project: ProjectSummary;
  /** Click anywhere on the row → drill into the project. */
  onOpen?: (projectId: number) => void;
  /** Click the small airplane icon → launch this .als in Ableton. */
  onLaunch?: (projectId: number) => void;
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

function fmtRelative(unixSec: number): string {
  const ms = unixSec * 1000;
  const days = Math.round((Date.now() - ms) / 86_400_000);
  if (days < 1) return 'today';
  if (days < 2) return 'yesterday';
  if (days < 30) return `${days}d ago`;
  if (days < 365) return `${Math.round(days / 30)}mo ago`;
  return `${Math.round(days / 365)}y ago`;
}

/**
 * Two-row stationery row: top line is name + key stats + timestamp; bottom
 * line (muted) is the parent path and tags. Stats use fixed-width columns so
 * BPM/time/tracks/length line up across rows. No icon glyphs — at small sizes
 * the field PNGs aren't legible; the labels carry the meaning.
 */
export function SongStrip({ project, onOpen, onLaunch }: SongStripProps) {
  const hasColor = project.color_tag != null;
  const colorVar = hasColor ? `var(--als-${project.color_tag! + 1})` : 'var(--rule-line-strong)';

  return (
    <button
      type="button"
      onClick={() => onOpen?.(project.id)}
      data-testid="song-strip"
      data-color-tag={project.color_tag ?? ''}
      className={clsx(
        'group relative w-full text-left',
        'flex flex-col gap-0.5 px-3 py-2',
        'bg-surface-card hover:bg-surface-sunken',
        'border border-rule-line rounded-card',
        'transition-colors duration-fast',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent',
      )}
    >
      {onLaunch ? (
        <span
          role="button"
          tabIndex={0}
          aria-label={`Open ${project.name} in Ableton`}
          data-testid="song-strip-launch"
          onClick={(e) => {
            e.stopPropagation();
            onLaunch(project.id);
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault();
              e.stopPropagation();
              onLaunch(project.id);
            }
          }}
          className={clsx(
            'absolute right-2 top-1/2 -translate-y-1/2',
            'inline-flex items-center justify-center w-6 h-6',
            'rounded-chip border border-rule-line bg-surface-card',
            'text-ink-secondary hover:text-ink-primary',
            'opacity-0 pointer-events-none',
            'group-hover:opacity-100 group-hover:pointer-events-auto',
            'group-focus-within:opacity-100 group-focus-within:pointer-events-auto',
            'focus-visible:opacity-100 focus-visible:pointer-events-auto',
            'transition-opacity duration-fast',
            'motion-reduce:transition-none',
          )}
        >
          <Sprite name="paper-airplane" size={14} />
        </span>
      ) : null}
      <span className="flex items-center gap-4">
        <span
          aria-hidden
          className="shrink-0 w-1.5 h-7 rounded-full"
          style={{ backgroundColor: colorVar }}
        />

        <span className="flex-1 min-w-0 font-medium text-ink-primary truncate text-[15px] leading-tight">
          {project.name}
        </span>

        <span className="hidden md:flex items-center gap-5 font-mono text-[12px] text-ink-secondary tabular-nums">
          <Stat label="bpm" value={project.tempo != null ? project.tempo.toFixed(0) : '—'} width="3.25rem" />
          <Stat label="meter" value={fmtTimeSig(project.time_sig_num, project.time_sig_den)} width="3rem" />
          <Stat label="tracks" value={project.track_count != null ? String(project.track_count) : '—'} width="3.25rem" />
          <Stat label="length" value={fmtSeconds(project.length_seconds)} width="3.5rem" />
          <Stat
            label="effort"
            value={project.effort_score != null ? String(project.effort_score) : '—'}
            width="2.5rem"
          />
        </span>

        <span className="shrink-0 font-mono text-[11px] text-ink-muted whitespace-nowrap min-w-[68px] text-right">
          {fmtRelative(project.last_modified)}
        </span>
      </span>

      <span className="flex items-center gap-3 pl-[22px] min-w-0">
        <span className="font-mono text-[11px] text-ink-muted truncate min-w-0 flex-1">
          {project.parent_dir}
        </span>
        {(project.tags?.length ?? 0) > 0 ? (
          <span className="hidden sm:flex shrink-0 items-center gap-1">
            {project.tags!.slice(0, 3).map((t) => (
              <span
                key={t}
                className="px-1.5 py-0.5 text-[10px] font-mono rounded-chip bg-paper-tint-blue text-ink-secondary border border-rule-line leading-none"
              >
                {t}
              </span>
            ))}
            {project.tags!.length > 3 ? (
              <span className="text-[10px] font-mono text-ink-muted">
                +{project.tags!.length - 3}
              </span>
            ) : null}
          </span>
        ) : null}
      </span>
    </button>
  );
}

function Stat({ label, value, width }: { label: string; value: string; width: string }) {
  return (
    <span
      className="inline-flex flex-col items-end leading-tight"
      style={{ width }}
      title={label}
    >
      <span className="text-ink-primary">{value}</span>
      <span className="text-[9px] uppercase tracking-wider text-ink-faint">{label}</span>
    </span>
  );
}
