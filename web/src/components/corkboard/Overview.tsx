import { useNavigate } from '@tanstack/react-router';
import type { ProjectDetail } from '../../lib/types';
import { useRepairFindings, useSubmitProposal } from '../../app/queries';
import { RepairPanel } from '../data/RepairPanel';

export function Overview({ project }: { project: ProjectDetail }) {
  const findings = useRepairFindings();
  const submit = useSubmitProposal();
  const navigate = useNavigate();
  const macImport = findings.data?.macImports.find((m) => m.projectId === project.id) ?? null;
  const missingSamples =
    findings.data?.missingSamples.filter((m) => m.projectId === project.id) ?? [];

  const handlePropose = (sel: { macImport: boolean; relinks: Record<string, string> }) => {
    const actions: Array<{ type: string; args: Record<string, unknown> }> = [];
    if (sel.macImport) {
      actions.push({ type: 'RepairMacPaths', args: { project_id: project.id } });
    }
    const relinkEntries = Object.entries(sel.relinks);
    if (relinkEntries.length) {
      actions.push({
        type: 'RelinkMissingSamples',
        args: {
          project_id: project.id,
          relinks: relinkEntries.map(([oldPath, newPath]) => ({ old: oldPath, new: newPath })),
        },
      });
    }
    if (!actions.length) return;
    submit.mutate(
      // The proposal API surface accepts arbitrary action shapes here; ProposedAction's
      // discriminated union is the strict view used by listProposals/getProposal.
      { actor: 'user', actions: actions as never, rationale: 'inline repair' },
      {
        onSuccess: ({ proposal_id }) => {
          navigate({ to: '/proposals', search: { id: proposal_id } as never });
        },
      },
    );
  };

  return (
    <div className="space-y-5">
      <RepairPanel
        macImport={
          macImport
            ? {
                projectId: macImport.projectId,
                macPathsCount: macImport.macPathsCount,
                projectInfoMissing: macImport.projectInfoMissing,
              }
            : null
        }
        missingSamples={missingSamples.map((m) => ({
          missingPath: m.missingPath,
          autoMatch: m.autoMatch,
          candidates: m.candidates,
        }))}
        onPropose={handlePropose}
      />
      {/* Headline stats */}
      <dl className="grid grid-cols-2 gap-2 text-[12px]">
        <Stat label="tempo" value={project.tempo != null ? `${project.tempo.toFixed(1)} BPM` : '—'} />
        <Stat
          label="time sig"
          value={
            project.time_sig_num != null ? `${project.time_sig_num}/${project.time_sig_den}` : '—'
          }
        />
        <Stat
          label="tracks"
          value={
            project.track_count != null
              ? `${project.track_count} (${project.audio_tracks ?? 0}a · ${project.midi_tracks ?? 0}m · ${project.return_tracks ?? 0}r)`
              : '—'
          }
        />
        <Stat label="length" value={secs(project.length_seconds)} />
        <Stat
          label="color"
          value={project.color_tag != null ? `als-${project.color_tag + 1}` : '—'}
        />
        <Stat label="archived" value={project.is_archived ? 'yes' : 'no'} />
      </dl>

      {/* Tags chips */}
      {project.tags.length > 0 ? (
        <section>
          <h3 className="text-[10px] uppercase tracking-wider text-ink-faint mb-1.5">tags</h3>
          <div className="flex flex-wrap gap-1.5">
            {project.tags.map((t) => (
              <span
                key={t}
                className="px-2 py-0.5 text-[11px] font-mono rounded-chip bg-paper-tint-blue text-ink-secondary border border-rule-line"
              >
                {t}
              </span>
            ))}
          </div>
        </section>
      ) : null}

      {/* Path block */}
      <section className="space-y-1">
        <h3 className="text-[10px] uppercase tracking-wider text-ink-faint">path</h3>
        <p className="font-mono text-[12px] break-all text-ink-primary leading-snug">
          {project.path}
        </p>
      </section>

      {/* Metadata block */}
      <section className="grid grid-cols-2 gap-x-4 gap-y-1.5 font-mono text-[11px]">
        <Meta label="live version" value={project.live_version ?? '—'} />
        <Meta label="last modified" value={date(project.last_modified)} />
        <Meta label="last scanned" value={date(project.last_scanned)} />
        <Meta label="hash" value={project.file_hash.slice(0, 16) + '…'} title={project.file_hash} />
      </section>

      {project.notes ? (
        <section className="space-y-1">
          <h3 className="text-[10px] uppercase tracking-wider text-ink-faint">notes</h3>
          <p className="text-[13px] text-ink-secondary leading-relaxed">{project.notes}</p>
        </section>
      ) : null}
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex flex-col gap-0.5 px-3 py-2 rounded-input bg-surface-sunken border border-rule-line">
      <span className="text-[10px] uppercase tracking-wider text-ink-faint font-mono">{label}</span>
      <span className="text-ink-primary font-medium">{value}</span>
    </div>
  );
}

function Meta({ label, value, title }: { label: string; value: string; title?: string }) {
  return (
    <div className="flex flex-col gap-0.5">
      <span className="text-[10px] uppercase tracking-wider text-ink-faint">{label}</span>
      <span className="text-ink-secondary truncate" title={title ?? value}>
        {value}
      </span>
    </div>
  );
}

function secs(s: number | null): string {
  if (s == null) return '—';
  const m = Math.floor(s / 60);
  const r = Math.round(s % 60);
  return `${m}:${r.toString().padStart(2, '0')}`;
}

function date(unix: number): string {
  return new Date(unix * 1000).toISOString().slice(0, 10);
}
