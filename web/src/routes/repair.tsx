import { useMemo, useState } from 'react';
import { useNavigate } from '@tanstack/react-router';
import { useRepairFindings, useSubmitProposal } from '../app/queries';
import { Shelf } from '../components/surface/Shelf';

interface Selection {
  macProjectIds: Set<number>;
  picks: Map<number, Map<string, string>>;
}

export function RepairRoute() {
  const findings = useRepairFindings();
  const submit = useSubmitProposal();
  const navigate = useNavigate();
  const [sel, setSel] = useState<Selection>(() => ({
    macProjectIds: new Set(),
    picks: new Map(),
  }));

  const macImports = findings.data?.macImports ?? [];
  const missingByProject = useMemo(() => {
    const map = new Map<number, NonNullable<typeof findings.data>['missingSamples']>();
    for (const m of findings.data?.missingSamples ?? []) {
      const list = map.get(m.projectId) ?? [];
      list.push(m);
      map.set(m.projectId, list);
    }
    return map;
  }, [findings.data]);

  const projectFullyResolvable = (projectId: number) => {
    const list = missingByProject.get(projectId) ?? [];
    return list.every((m) => m.autoMatch != null);
  };

  const selectedCount =
    sel.macProjectIds.size +
    Array.from(sel.picks.keys()).filter((pid) => missingByProject.has(pid)).length;

  const handlePropose = () => {
    const actions: Array<{ type: string; args: Record<string, unknown> }> = [];
    for (const pid of sel.macProjectIds) {
      actions.push({ type: 'RepairMacPaths', args: { project_id: pid } });
    }
    for (const [pid] of missingByProject) {
      if (!sel.picks.has(pid) && !projectFullyResolvable(pid)) continue;
      const list = missingByProject.get(pid)!;
      const overrides = sel.picks.get(pid) ?? new Map();
      const relinks: Array<{ old: string; new: string }> = [];
      for (const m of list) {
        const chosen = overrides.get(m.missingPath) ?? m.autoMatch?.path;
        if (chosen) relinks.push({ old: m.missingPath, new: chosen });
      }
      if (relinks.length) {
        actions.push({
          type: 'RelinkMissingSamples',
          args: { project_id: pid, relinks },
        });
      }
    }
    if (!actions.length) return;
    submit.mutate(
      { actor: 'user', actions: actions as never, rationale: 'bulk repair' },
      {
        onSuccess: ({ proposal_id }) => {
          navigate({ to: '/proposals', search: { id: proposal_id } as never });
        },
      },
    );
  };

  if (findings.isLoading) return <div className="p-6">loading…</div>;
  if (findings.isError) return <div className="p-6 text-error">{String(findings.error)}</div>;

  return (
    <div className="p-6 space-y-6">
      <h1 className="font-display text-2xl">Repair</h1>

      <Shelf title={`Mac imports · ${macImports.length}`}>
        {macImports.length === 0 ? (
          <p className="text-sm text-ink-muted">No Mac-imported projects.</p>
        ) : (
          <ul className="space-y-1 w-full">
            {macImports.map((m) => (
              <li
                key={m.projectId}
                data-testid={`mac-row-${m.projectId}`}
                className="flex items-center gap-2 px-2 py-1 rounded-input border border-rule-line"
              >
                <input
                  type="checkbox"
                  checked={sel.macProjectIds.has(m.projectId)}
                  onChange={(e) =>
                    setSel((prev) => {
                      const next = new Set(prev.macProjectIds);
                      if (e.target.checked) next.add(m.projectId);
                      else next.delete(m.projectId);
                      return { ...prev, macProjectIds: next };
                    })
                  }
                />
                <span className="font-mono text-sm">{m.name}</span>
                <span className="text-xs text-ink-faint">
                  {m.macPathsCount} mac paths
                  {m.projectInfoMissing ? ' · no Project Info' : ''}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Shelf>

      <Shelf title={`Missing samples · ${missingByProject.size}`}>
        {missingByProject.size === 0 ? (
          <p className="text-sm text-ink-muted">No projects with missing samples.</p>
        ) : (
          <ul className="space-y-1 w-full">
            {Array.from(missingByProject.entries()).map(([pid, list]) => {
              const resolvable = projectFullyResolvable(pid);
              const checked = sel.picks.has(pid);
              return (
                <li
                  key={pid}
                  data-testid={`missing-row-${pid}`}
                  className="flex items-center gap-2 px-2 py-1 rounded-input border border-rule-line"
                >
                  <input
                    type="checkbox"
                    checked={checked}
                    disabled={!resolvable}
                    onChange={(e) =>
                      setSel((prev) => {
                        const nextPicks = new Map(prev.picks);
                        if (e.target.checked) {
                          nextPicks.set(pid, new Map());
                        } else {
                          nextPicks.delete(pid);
                        }
                        return { ...prev, picks: nextPicks };
                      })
                    }
                  />
                  <span className="font-mono text-sm">{list[0]!.projectName}</span>
                  <span className="text-xs text-ink-faint">
                    {list.length} missing sample{list.length === 1 ? '' : 's'}
                  </span>
                  {!resolvable ? (
                    <span className="text-xs text-ink-faint italic">needs review</span>
                  ) : null}
                </li>
              );
            })}
          </ul>
        )}
      </Shelf>

      <div className="border-t border-rule-line pt-3 flex items-center gap-3">
        <span className="text-sm text-ink-muted">{selectedCount} selected</span>
        <button
          type="button"
          disabled={selectedCount === 0 || submit.isPending}
          onClick={handlePropose}
          className="px-3 py-1 text-sm rounded-input border border-accent/40 bg-accent/10 text-accent hover:bg-accent/20 disabled:opacity-50"
        >
          Propose batch
        </button>
      </div>
    </div>
  );
}
