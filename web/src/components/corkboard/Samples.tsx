import type { Project } from '../../lib/types';

/**
 * Samples are not on the mock fixture; render a placeholder for v0.1.
 * Backend wiring will replace this with a real sample list.
 */
export function Samples({ project }: { project: Project }) {
  const fake = [
    { path: `${project.parent_dir}/Samples/kick.wav`, missing: false },
    { path: `${project.parent_dir}/Samples/snare.wav`, missing: false },
    { path: 'C:/Users/jtfow/Samples/missing-loop.wav', missing: true },
  ];
  return (
    <table className="w-full font-mono text-sm">
      <thead className="text-ink-muted">
        <tr>
          <th className="text-left">path</th>
          <th className="text-left">status</th>
        </tr>
      </thead>
      <tbody>
        {fake.map((s, i) => (
          <tr key={i} className="border-t border-rule-line/30">
            <td className="py-1 truncate max-w-[24rem]" title={s.path}>{s.path}</td>
            <td className={s.missing ? 'text-accent-action' : 'text-pin-green'}>
              {s.missing ? 'missing' : 'ok'}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
