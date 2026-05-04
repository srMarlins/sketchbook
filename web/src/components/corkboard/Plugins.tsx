import type { ProjectDetail } from '../../lib/types';

export function Plugins({ project }: { project: ProjectDetail }) {
  if (project.plugins.length === 0) {
    return <p className="text-sm text-ink-muted">No plugins detected.</p>;
  }
  return (
    <table className="w-full font-mono text-sm">
      <thead className="text-ink-muted">
        <tr>
          <th className="text-left py-1">name</th>
          <th className="text-left py-1">type</th>
          <th className="text-left py-1">track</th>
        </tr>
      </thead>
      <tbody>
        {project.plugins.map((p, i) => (
          <tr key={i} className="border-t border-rule-line">
            <td className="py-1 break-words">{p.plugin_name}</td>
            <td className="text-ink-muted">{p.plugin_type ?? '—'}</td>
            <td className="text-ink-muted">{p.track_name ?? '—'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
