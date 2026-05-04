import type { ProjectDetail } from '../../lib/types';
import { Sprite } from '../primitives/Sprite';

export function Overview({ project }: { project: ProjectDetail }) {
  return (
    <div className="space-y-4">
      <dl className="grid grid-cols-[8rem_1fr] gap-y-2 font-mono text-sm">
        <Row label="path">{project.path}</Row>
        <Row label="parent">{project.parent_dir}</Row>
        <Row label="tempo">
          <Sprite name="bpm" size={14} className="mr-1" />
          {project.tempo?.toFixed(1) ?? '—'} BPM
        </Row>
        <Row label="time sig">
          {project.time_sig_num != null ? `${project.time_sig_num}/${project.time_sig_den}` : '—'}
        </Row>
        <Row label="tracks">
          {project.track_count ?? '—'}{' '}
          {project.track_count != null
            ? `(${project.audio_tracks ?? 0} audio · ${project.midi_tracks ?? 0} midi · ${project.return_tracks ?? 0} return)`
            : ''}
        </Row>
        <Row label="length">{secs(project.length_seconds)}</Row>
        <Row label="live version">{project.live_version ?? '—'}</Row>
        <Row label="last modified">{date(project.last_modified)}</Row>
        <Row label="last scanned">{date(project.last_scanned)}</Row>
        <Row label="hash">{project.file_hash}</Row>
        <Row label="archived">{project.is_archived ? 'yes' : 'no'}</Row>
        <Row label="color">
          {project.color_tag != null ? `als-${project.color_tag + 1}` : '—'}
        </Row>
        <Row label="tags">{project.tags.length === 0 ? '—' : project.tags.join(', ')}</Row>
      </dl>
      {project.notes ? (
        <section>
          <h3 className="text-base font-semibold mb-1">Notes</h3>
          <p className="text-sm">{project.notes}</p>
        </section>
      ) : null}
    </div>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <>
      <dt className="text-ink-muted">{label}</dt>
      <dd className="break-words min-w-0">{children}</dd>
    </>
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
