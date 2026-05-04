import type { ProjectDetail } from '../../lib/types';

export function Samples({ project }: { project: ProjectDetail }) {
  if (project.samples.length === 0) {
    return <p className="text-sm text-ink-muted">No samples referenced.</p>;
  }
  return (
    <table className="w-full font-mono text-sm">
      <thead className="text-ink-muted">
        <tr>
          <th className="text-left py-1">path</th>
          <th className="text-left py-1">status</th>
        </tr>
      </thead>
      <tbody>
        {project.samples.map((s, i) => (
          <tr key={i} className="border-t border-rule-line">
            <td className="py-1 break-all" title={s.sample_path}>
              {s.sample_path}
            </td>
            <td
              className={s.is_missing ? 'text-accent-danger' : 'text-accent-positive'}
            >
              {s.is_missing ? 'missing' : 'ok'}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
