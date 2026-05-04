import type { ProjectDetail } from '../../lib/types';

export function Tracks({ project }: { project: ProjectDetail }) {
  const audio = project.audio_tracks ?? 0;
  const midi = project.midi_tracks ?? 0;
  const ret = project.return_tracks ?? 0;
  if (audio + midi + ret === 0) {
    return <p className="text-sm text-ink-muted">No tracks scanned.</p>;
  }
  const rows = [
    ...Array.from({ length: audio }, (_, i) => ({ type: 'audio' as const, name: `audio ${i + 1}` })),
    ...Array.from({ length: midi }, (_, i) => ({ type: 'midi' as const, name: `midi ${i + 1}` })),
    ...Array.from({ length: ret }, (_, i) => ({ type: 'return' as const, name: `return ${i + 1}` })),
  ];
  return (
    <table className="w-full font-mono text-sm">
      <thead className="text-ink-muted">
        <tr>
          <th className="text-left py-1">#</th>
          <th className="text-left py-1">type</th>
          <th className="text-left py-1">name</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r, i) => (
          <tr key={i} className="border-t border-rule-line">
            <td className="py-1">{i + 1}</td>
            <td className="text-ink-muted">{r.type}</td>
            <td>{r.name}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
