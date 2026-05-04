import type { Project } from '../../lib/types';

export function Tracks({ project }: { project: Project }) {
  const rows = [
    ...Array.from({ length: project.audio_tracks }, (_, i) => ({
      idx: i + 1,
      type: 'audio' as const,
      name: `audio ${i + 1}`,
    })),
    ...Array.from({ length: project.midi_tracks }, (_, i) => ({
      idx: i + 1,
      type: 'midi' as const,
      name: `midi ${i + 1}`,
    })),
    ...Array.from({ length: project.return_tracks }, (_, i) => ({
      idx: i + 1,
      type: 'return' as const,
      name: `return ${i + 1}`,
    })),
  ];

  return (
    <table className="w-full font-mono text-sm">
      <thead className="text-ink-muted">
        <tr>
          <th className="text-left">#</th>
          <th className="text-left">type</th>
          <th className="text-left">name</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r, i) => (
          <tr key={i} className="border-t border-rule-line/30">
            <td className="py-1">{i + 1}</td>
            <td>{r.type}</td>
            <td>{r.name}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
