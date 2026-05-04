import type { ProjectDetail, SampleRow } from '../../lib/types';

export function Samples({ project }: { project: ProjectDetail }) {
  if (project.samples.length === 0) {
    return <p className="text-sm text-ink-muted">No samples referenced.</p>;
  }

  const total = project.samples.length;
  const missing = project.samples.filter((s) => s.is_missing).length;
  const ok = total - missing;

  // Group by parent directory.
  const groups = new Map<string, SampleRow[]>();
  for (const s of project.samples) {
    const dir = parentDir(s.sample_path);
    const arr = groups.get(dir) ?? [];
    arr.push(s);
    groups.set(dir, arr);
  }
  // Missing-first groups, then alphabetical.
  const ordered = [...groups.entries()].sort(([a, sa], [b, sb]) => {
    const am = sa.some((s) => s.is_missing);
    const bm = sb.some((s) => s.is_missing);
    if (am !== bm) return am ? -1 : 1;
    return a.localeCompare(b);
  });

  return (
    <div className="space-y-4">
      {/* Summary header */}
      <div className="grid grid-cols-3 gap-2">
        <Stat label="total" value={String(total)} tone="default" />
        <Stat label="ok" value={String(ok)} tone="ok" />
        <Stat label="missing" value={String(missing)} tone={missing > 0 ? 'danger' : 'default'} />
      </div>

      {/* Sample groups */}
      <div className="space-y-2">
        {ordered.map(([dir, samples]) => {
          const groupMissing = samples.filter((s) => s.is_missing).length;
          return (
            <section
              key={dir}
              className="rounded-card border border-rule-line bg-surface-sunken overflow-hidden"
            >
              <header className="flex items-center justify-between gap-3 px-3 py-1.5 border-b border-rule-line">
                <span
                  className="font-mono text-[11px] text-ink-muted truncate min-w-0"
                  title={dir}
                >
                  {dir || '/'}
                </span>
                <span className="shrink-0 flex items-center gap-2 font-mono text-[10px] tabular-nums">
                  <span className="text-ink-faint">{samples.length}</span>
                  {groupMissing > 0 && (
                    <span className="px-1.5 py-0.5 rounded-chip bg-accent-danger/15 text-accent-danger border border-accent-danger/30">
                      {groupMissing} missing
                    </span>
                  )}
                </span>
              </header>
              <ul className="divide-y divide-rule-line">
                {samples.map((s, i) => (
                  <li
                    key={`${s.sample_path}-${i}`}
                    className={
                      'flex items-center gap-2 px-3 py-1.5 ' +
                      (s.is_missing ? 'bg-accent-danger/5' : '')
                    }
                  >
                    <span
                      aria-hidden
                      className={
                        'shrink-0 w-1.5 h-4 rounded-full ' +
                        (s.is_missing ? 'bg-accent-danger' : 'bg-accent-positive/70')
                      }
                    />
                    <span
                      className="font-mono text-[12px] text-ink-primary truncate min-w-0 flex-1"
                      title={s.sample_path}
                    >
                      {basename(s.sample_path)}
                    </span>
                    {s.is_missing && (
                      <span className="shrink-0 font-mono text-[10px] uppercase tracking-wider text-accent-danger">
                        missing
                      </span>
                    )}
                  </li>
                ))}
              </ul>
            </section>
          );
        })}
      </div>
    </div>
  );
}

function Stat({
  label,
  value,
  tone,
}: {
  label: string;
  value: string;
  tone: 'default' | 'ok' | 'danger';
}) {
  const valueClass =
    tone === 'danger'
      ? 'text-accent-danger'
      : tone === 'ok'
        ? 'text-accent-positive'
        : 'text-ink-primary';
  return (
    <div className="flex flex-col gap-0.5 px-3 py-2 rounded-input bg-surface-sunken border border-rule-line">
      <span className="text-[10px] uppercase tracking-wider text-ink-faint font-mono">
        {label}
      </span>
      <span className={`text-lg font-medium tabular-nums leading-none ${valueClass}`}>
        {value}
      </span>
    </div>
  );
}

function parentDir(p: string): string {
  const norm = p.replace(/\\/g, '/');
  const idx = norm.lastIndexOf('/');
  return idx === -1 ? '' : norm.slice(0, idx);
}

function basename(p: string): string {
  const norm = p.replace(/\\/g, '/');
  const idx = norm.lastIndexOf('/');
  return idx === -1 ? p : norm.slice(idx + 1);
}
