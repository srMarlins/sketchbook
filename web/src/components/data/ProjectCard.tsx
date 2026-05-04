import clsx from 'clsx';
import { Sprite } from '../primitives/Sprite';
import { Button } from '../inputs/Button';
import { useOpenProject } from '../../app/queries';
import type { ProjectSummary } from '../../lib/types';

export interface ProjectCardProps {
  project: ProjectSummary;
  /** Called when the card body (not the action button) is clicked. */
  onOpen?: (projectId: number) => void;
  /**
   * Called when "Open in Ableton" is clicked. If omitted, the component fires
   * `useOpenProject().mutate(id)` itself.
   */
  onOpenInAbleton?: (projectId: number) => void;
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

const SEGMENTS = 10;

/**
 * Netflix-style project card for home shelves. Compact stationery card
 * (~240px wide) with a color dot, project name (2 lines), compact stats,
 * an effort bar, last-modified, and a primary "Open in Ableton" button.
 *
 * The card body is itself clickable (separate from the button) to open the
 * detail view — passes `project.id` to `onOpen`.
 */
export function ProjectCard({ project, onOpen, onOpenInAbleton }: ProjectCardProps) {
  const openMut = useOpenProject();
  const colorVar =
    project.color_tag != null ? `var(--als-${project.color_tag + 1})` : 'transparent';

  const score = project.effort_score;
  const filledCount = score == null ? 0 : Math.round((score / 100) * SEGMENTS);

  function handleOpenAbleton() {
    if (onOpenInAbleton) {
      onOpenInAbleton(project.id);
      return;
    }
    openMut.mutate(project.id);
  }

  return (
    <article
      className={clsx(
        'group relative flex flex-col',
        'w-60 shrink-0 bg-surface-card rounded-card shadow-card',
        'border border-rule-line/60',
        'motion-safe:transition-all motion-safe:duration-fast motion-safe:ease-paper',
        'motion-safe:hover:-translate-y-0.5 motion-safe:hover:shadow-lift',
      )}
      data-testid="project-card"
      data-color-tag={project.color_tag ?? ''}
    >
      <button
        type="button"
        onClick={() => onOpen?.(project.id)}
        className={clsx(
          'text-left px-3 pt-3 pb-2 flex flex-col gap-2',
          'rounded-card',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent',
        )}
        data-testid="project-card-body"
      >
        <div className="flex items-start gap-2">
          <span
            aria-hidden
            data-testid="color-dot"
            data-color-tag={project.color_tag ?? ''}
            className="mt-1 shrink-0 w-2.5 h-2.5 rounded-full"
            style={{
              backgroundColor: colorVar,
              border: project.color_tag == null ? '1px dashed var(--rule-line)' : 'none',
            }}
          />
          <h3 className="min-w-0 flex-1 font-semibold text-ink-primary text-[14px] leading-snug line-clamp-2">
            {project.name}
          </h3>
        </div>

        <dl className="flex flex-wrap items-center gap-x-2 gap-y-1 font-mono text-[11px] text-ink-secondary tabular-nums">
          <Stat icon="bpm" label={project.tempo != null ? project.tempo.toFixed(0) : '—'} />
          <Stat icon="time-sig" label={fmtTimeSig(project.time_sig_num, project.time_sig_den)} />
          <Stat icon="tracks" label={project.track_count != null ? String(project.track_count) : '—'} />
          <Stat icon="length" label={fmtSeconds(project.length_seconds)} />
        </dl>

        <div className="flex items-center gap-2">
          <div
            className="flex flex-1 gap-[2px]"
            role="meter"
            aria-label="effort score"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={score ?? 0}
          >
            {Array.from({ length: SEGMENTS }).map((_, i) => {
              const filled = i < filledCount;
              return (
                <span
                  key={i}
                  data-testid="effort-seg"
                  data-filled={filled ? 'true' : 'false'}
                  className={clsx(
                    'h-1.5 flex-1 rounded-sm',
                    filled ? 'bg-accent' : 'bg-rule-line',
                  )}
                />
              );
            })}
          </div>
          <span className="font-mono text-[11px] text-ink-muted tabular-nums w-6 text-right">
            {score == null ? '—' : score}
          </span>
        </div>

        <p className="font-mono text-[10px] text-ink-muted">
          {fmtRelative(project.last_modified)}
        </p>
      </button>

      <div className="px-3 pb-3 pt-1">
        <Button
          variant="primary"
          size="sm"
          onClick={handleOpenAbleton}
          disabled={onOpenInAbleton ? false : openMut.isPending}
          className="w-full justify-center"
        >
          {!onOpenInAbleton && openMut.isPending ? 'opening…' : 'Open in Ableton'}
        </Button>
      </div>
    </article>
  );
}

function Stat({
  icon,
  label,
}: {
  icon: 'bpm' | 'time-sig' | 'tracks' | 'length';
  label: string;
}) {
  return (
    <span className="inline-flex items-center gap-1 whitespace-nowrap">
      <Sprite name={icon} size={11} className="text-ink-muted" />
      <span>{label}</span>
    </span>
  );
}
