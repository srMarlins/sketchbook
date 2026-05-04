import type { Project } from '../../lib/types';

export function Plugins(_props: { project: Project }) {
  const fake = [
    { name: 'Operator', type: 'instrument', track: 'midi 1' },
    { name: 'Reverb', type: 'audio-effect', track: 'audio 2' },
    { name: 'EQ Eight', type: 'audio-effect', track: 'audio 3' },
  ];
  return (
    <table className="w-full font-mono text-sm">
      <thead className="text-ink-muted">
        <tr>
          <th className="text-left">name</th>
          <th className="text-left">type</th>
          <th className="text-left">track</th>
        </tr>
      </thead>
      <tbody>
        {fake.map((p, i) => (
          <tr key={i} className="border-t border-rule-line/30">
            <td className="py-1">{p.name}</td>
            <td>{p.type}</td>
            <td>{p.track}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
