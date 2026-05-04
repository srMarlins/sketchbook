import clsx from 'clsx';
import { Sprite } from '../primitives/Sprite';
import type { ProjectSummary } from '../../lib/types';

export interface SongStripProps {
  project: ProjectSummary;
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
 * A dense, paper-stationery row for a project. Color tag shows as a small
 * chip on the left edge, not as the whole strip background. Tags get small
 * rounded chips on the right.
 */
export function SongStrip({ project, onOpen }: SongStripProps) {
  // backend color_tag is 0..13; map to als-1..als-14 var name
  const colorVar = project.color_tag != null ? `var(--als-${project.color_tag + 1})` : 'transparent';

  return (
    <button
      type="button"
      onClick={() => onOpen?.(project.id)}
      className={clsx(
        'group relative w-full text-left',
        'flex items-center gap-3 px-3 py-2',
        'bg-surface-card hover:bg-surface-sunken',
        'border border-rule-line rounded-card',
        'transition-colors duration-fast',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent',
      )}
      data-color-tag={project.color_tag}
    >
      <span
        aria-hidden
        className="shrink-0 w-1.5 h-8 rounded-full"
        style={{ backgroundColor: colorVar, border: project.color_tag == null ? '1px dashed var(--rule-line)' : 'none' }}
      />

      <span className="min-w-0 flex-1 flex flex-col">
        <span className="font-medium text-ink-primary truncate text-[15px] leading-tight">
          {project.name}
        </span>
        <span className="font-mono text-[11px] text-ink-muted truncate">
          {project.parent_dir}
        </span>
      </span>

      <span className="hidden md:flex items-center gap-3 font-mono text-[12px] text-ink-secondary tabular-nums">
        <Field icon="bpm" label={project.tempo != null ? project.tempo.toFixed(0) : '—'} />
        <Field icon="time-sig" label={fmtTimeSig(project.time_sig_num, project.time_sig_den)} />
        <Field icon="tracks" label={project.track_count != null ? String(project.track_count) : '—'} />
        <Field icon="length" label={fmtSeconds(project.length_seconds)} />
      </span>

      {project.tags.length > 0 ? (
        <span className="hidden lg:flex items-center gap-1">
          {project.tags.slice(0, 3).map((t) => (
            <span
              key={t}
              className="px-1.5 py-0.5 text-[10px] font-mono rounded-chip bg-paper-tint-blue text-ink-secondary border border-rule-line"
            >
              {t}
            </span>
          ))}
          {project.tags.length > 3 ? (
            <span className="text-[10px] font-mono text-ink-muted">+{project.tags.length - 3}</span>
          ) : null}
        </span>
      ) : null}

      <span className="shrink-0 font-mono text-[11px] text-ink-muted whitespace-nowrap min-w-[64px] text-right">
        {fmtRelative(project.last_modified)}
      </span>
    </button>
  );
}

function Field({
  icon,
  label,
}: {
  icon: 'bpm' | 'time-sig' | 'tracks' | 'length';
  label: string;
}) {
  return (
    <span className="inline-flex items-center gap-1 whitespace-nowrap">
      <Sprite name={icon} size={12} className="text-ink-muted" />
      <span>{label}</span>
    </span>
  );
}
