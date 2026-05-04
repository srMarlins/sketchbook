import type { PluginRow, ProjectDetail } from '../../lib/types';

export function Plugins({ project }: { project: ProjectDetail }) {
  if (project.plugins.length === 0) {
    return <p className="text-sm text-ink-muted">No plugins detected.</p>;
  }

  const byType = new Map<string, number>();
  for (const p of project.plugins) {
    const k = p.plugin_type ?? 'other';
    byType.set(k, (byType.get(k) ?? 0) + 1);
  }

  const byTrack = new Map<string, PluginRow[]>();
  for (const p of project.plugins) {
    const k = p.track_name ?? '— unassigned —';
    const arr = byTrack.get(k) ?? [];
    arr.push(p);
    byTrack.set(k, arr);
  }
  const trackGroups = [...byTrack.entries()].sort(([a], [b]) => a.localeCompare(b));

  return (
    <div className="space-y-5">
      {/* Summary chips */}
      <div className="flex items-baseline justify-between gap-3">
        <span className="text-[10px] uppercase tracking-wider text-ink-faint font-mono">
          {project.plugins.length} plugin{project.plugins.length === 1 ? '' : 's'} ·{' '}
          {trackGroups.length} track{trackGroups.length === 1 ? '' : 's'}
        </span>
        <div className="flex gap-1.5 flex-wrap justify-end">
          {[...byType.entries()]
            .sort(([, a], [, b]) => b - a)
            .map(([t, n]) => (
              <span
                key={t}
                className={`px-2 py-0.5 text-[11px] font-mono rounded-chip border border-rule-line ${typeTint(t)}`}
              >
                {n} {t}
              </span>
            ))}
        </div>
      </div>

      {/* Grouped by track */}
      <div className="space-y-3">
        {trackGroups.map(([trackName, plugins]) => (
          <section
            key={trackName}
            className="rounded-card border border-rule-line bg-surface-sunken"
          >
            <header className="flex items-baseline justify-between px-3 py-1.5 border-b border-rule-line">
              <span className="font-mono text-[12px] text-ink-secondary truncate" title={trackName}>
                {trackName}
              </span>
              <span className="font-mono text-[10px] text-ink-faint tabular-nums">
                {plugins.length}
              </span>
            </header>
            <ul className="divide-y divide-rule-line">
              {plugins.map((p, i) => (
                <li
                  key={`${p.plugin_name}-${i}`}
                  className="flex items-center gap-2 px-3 py-1.5"
                >
                  <span
                    className={`shrink-0 px-1.5 py-0.5 text-[10px] font-mono rounded-chip border border-rule-line ${typeTint(p.plugin_type)}`}
                  >
                    {p.plugin_type ?? 'other'}
                  </span>
                  <span className="font-mono text-[12px] text-ink-primary truncate" title={p.plugin_name}>
                    {p.plugin_name}
                  </span>
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>
    </div>
  );
}

function typeTint(t: string | null | undefined): string {
  switch ((t ?? '').toLowerCase()) {
    case 'vst3':
      return 'bg-paper-tint-blue text-ink-secondary';
    case 'vst2':
    case 'vst':
      return 'bg-paper-tint-sage text-ink-secondary';
    case 'au':
      return 'bg-paper-tint-rose text-ink-secondary';
    case 'native':
    case 'live':
      return 'bg-paper-tint-cream text-ink-secondary';
    default:
      return 'bg-surface-card text-ink-muted';
  }
}
