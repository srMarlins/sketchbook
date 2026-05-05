import { useState } from 'react';
import clsx from 'clsx';

export interface RepairPanelMissing {
  missingPath: string;
  autoMatch: { path: string; filename: string; sizeBytes: number } | null;
  candidates: { path: string; filename: string; sizeBytes: number }[];
}

export interface RepairPanelProps {
  macImport: { projectId: number; macPathsCount: number; projectInfoMissing: boolean } | null;
  missingSamples: RepairPanelMissing[];
  onPropose: (selection: { macImport: boolean; relinks: Record<string, string> }) => void;
}

export function RepairPanel({ macImport, missingSamples, onPropose }: RepairPanelProps) {
  const [picks, setPicks] = useState<Record<string, string>>({});
  const [openPicker, setOpenPicker] = useState<string | null>(null);

  const hasIssues = macImport != null || missingSamples.length > 0;
  if (!hasIssues) return null;

  const resolvedRelinks: Record<string, string> = {};
  for (const m of missingSamples) {
    const chosen = picks[m.missingPath] ?? m.autoMatch?.path;
    if (chosen) resolvedRelinks[m.missingPath] = chosen;
  }

  return (
    <section
      data-testid="repair-panel"
      className="space-y-2 rounded-input bg-paper-tint-orange/40 border border-rule-line p-3"
    >
      <h3 className="text-[10px] uppercase tracking-wider text-ink-faint font-mono">
        Needs attention
      </h3>

      {macImport ? (
        <div className="flex flex-wrap gap-1.5 items-center">
          <span className="px-2 py-0.5 text-[11px] font-mono rounded-chip bg-paper-tint-blue text-ink-secondary border border-rule-line">
            {macImport.macPathsCount} mac paths
          </span>
          {macImport.projectInfoMissing ? (
            <span className="px-2 py-0.5 text-[11px] font-mono rounded-chip bg-paper-tint-blue text-ink-secondary border border-rule-line">
              no Project Info
            </span>
          ) : null}
        </div>
      ) : null}

      {missingSamples.map((m) => {
        const chosen = picks[m.missingPath] ?? m.autoMatch?.path ?? null;
        const filename = m.missingPath.split(/[\\/]/).pop() ?? m.missingPath;
        return (
          <div
            key={m.missingPath}
            className="flex items-center gap-2 text-[12px] flex-wrap"
            data-testid={`repair-row-${filename}`}
          >
            <span className="font-mono text-ink-primary">{filename}</span>
            {chosen ? (
              <span className="text-ink-secondary font-mono text-[11px]">✓ {chosen}</span>
            ) : m.candidates.length === 0 ? (
              <span className="text-ink-faint font-mono text-[11px]">no match found</span>
            ) : (
              <button
                type="button"
                onClick={() =>
                  setOpenPicker((prev) => (prev === m.missingPath ? null : m.missingPath))
                }
                className="px-2 py-0.5 text-[11px] rounded-input border border-rule-line hover:bg-surface-sunken"
              >
                Pick candidate
              </button>
            )}
            {openPicker === m.missingPath ? (
              <ul className="basis-full ml-4 space-y-0.5">
                {m.candidates.map((c) => (
                  <li key={c.path}>
                    <button
                      type="button"
                      name={c.path}
                      onClick={() => {
                        setPicks((prev) => ({ ...prev, [m.missingPath]: c.path }));
                        setOpenPicker(null);
                      }}
                      className="px-2 py-0.5 text-[11px] font-mono text-ink-secondary hover:text-ink-primary"
                    >
                      {c.path}
                    </button>
                  </li>
                ))}
              </ul>
            ) : null}
          </div>
        );
      })}

      <div className="pt-1">
        <button
          type="button"
          onClick={() =>
            onPropose({ macImport: macImport != null, relinks: resolvedRelinks })
          }
          className={clsx(
            'px-3 py-1 text-[12px] rounded-input border border-accent/40 bg-accent/10 text-accent',
            'hover:bg-accent/20 transition-colors',
          )}
        >
          Propose repair
        </button>
      </div>
    </section>
  );
}
