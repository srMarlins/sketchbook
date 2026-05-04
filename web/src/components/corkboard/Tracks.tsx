import type { ProjectDetail } from '../../lib/types';

export function Tracks({ project }: { project: ProjectDetail }) {
  const audio = project.audio_tracks ?? 0;
  const midi = project.midi_tracks ?? 0;
  const ret = project.return_tracks ?? 0;
  const total = audio + midi + ret;

  if (total === 0) {
    return <p className="text-sm text-ink-muted">No tracks scanned.</p>;
  }

  const pct = (n: number) => (total === 0 ? 0 : (n / total) * 100);

  return (
    <div className="space-y-5">
      {/* Total + breakdown bar */}
      <section className="space-y-2">
        <div className="flex items-baseline justify-between">
          <span className="text-[10px] uppercase tracking-wider text-ink-faint font-mono">
            track count
          </span>
          <span className="font-mono text-sm text-ink-secondary tabular-nums">
            {total} total
          </span>
        </div>
        <div className="h-3 w-full rounded-full overflow-hidden flex border border-rule-line bg-surface-sunken">
          {audio > 0 && (
            <div
              className="bg-paper-tint-blue"
              style={{ width: `${pct(audio)}%` }}
              title={`${audio} audio (${pct(audio).toFixed(0)}%)`}
            />
          )}
          {midi > 0 && (
            <div
              className="bg-paper-tint-sage"
              style={{ width: `${pct(midi)}%` }}
              title={`${midi} midi (${pct(midi).toFixed(0)}%)`}
            />
          )}
          {ret > 0 && (
            <div
              className="bg-paper-tint-rose"
              style={{ width: `${pct(ret)}%` }}
              title={`${ret} return (${pct(ret).toFixed(0)}%)`}
            />
          )}
        </div>
      </section>

      {/* Stat cards by type */}
      <div className="grid grid-cols-3 gap-2">
        <TrackStat label="audio" count={audio} pct={pct(audio)} swatch="bg-paper-tint-blue" />
        <TrackStat label="midi" count={midi} pct={pct(midi)} swatch="bg-paper-tint-sage" />
        <TrackStat label="return" count={ret} pct={pct(ret)} swatch="bg-paper-tint-rose" />
      </div>

      <p className="text-[11px] text-ink-faint italic">
        Per-track names aren't indexed yet. Open the project in Ableton for full track detail.
      </p>
    </div>
  );
}

function TrackStat({
  label,
  count,
  pct,
  swatch,
}: {
  label: string;
  count: number;
  pct: number;
  swatch: string;
}) {
  return (
    <div className="flex flex-col gap-1 px-3 py-2.5 rounded-input bg-surface-sunken border border-rule-line">
      <div className="flex items-center gap-1.5">
        <span aria-hidden className={`w-2 h-2 rounded-full ${swatch}`} />
        <span className="text-[10px] uppercase tracking-wider text-ink-faint font-mono">
          {label}
        </span>
      </div>
      <span className="text-xl text-ink-primary font-medium tabular-nums leading-none">
        {count}
      </span>
      <span className="text-[10px] text-ink-muted font-mono tabular-nums">
        {count === 0 ? '—' : `${pct.toFixed(0)}%`}
      </span>
    </div>
  );
}
