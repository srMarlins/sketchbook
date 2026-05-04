import type { ProjectSummary } from '../lib/types';

export interface DerivedNotebook {
  id: string;
  title: string;
  kind: 'lined' | 'kraft' | 'manila';
  count: number;
  lastUpdated: number | null;
  filter: (p: ProjectSummary) => boolean;
}

export function deriveNotebooks(projects: ProjectSummary[]): DerivedNotebook[] {
  const out: DerivedNotebook[] = [];

  // Inbox: projects with parent_dir directly under the root (no year subfolder).
  const inbox = projects.filter((p) => !/\d{4}/.test(p.parent_dir));
  if (inbox.length > 0) {
    out.push({
      id: 'inbox',
      title: 'Inbox',
      kind: 'manila',
      count: inbox.length,
      lastUpdated: maxOf(inbox, (p) => p.last_modified),
      filter: (p) => !/\d{4}/.test(p.parent_dir),
    });
  }

  // Years: one notebook per year.
  const yearMap = new Map<string, ProjectSummary[]>();
  for (const p of projects) {
    const m = /\b(19|20)\d{2}\b/.exec(p.parent_dir);
    if (!m) continue;
    const year = m[0];
    if (!yearMap.has(year)) yearMap.set(year, []);
    yearMap.get(year)!.push(p);
  }
  for (const [year, items] of [...yearMap.entries()].sort((a, b) => Number(b[0]) - Number(a[0]))) {
    out.push({
      id: `year-${year}`,
      title: year,
      kind: 'lined',
      count: items.length,
      lastUpdated: maxOf(items, (p) => p.last_modified),
      filter: (p) => p.parent_dir.includes(year),
    });
  }

  // Tags: one notebook per active tag.
  const tagMap = new Map<string, ProjectSummary[]>();
  for (const p of projects) {
    for (const t of p.tags) {
      if (!tagMap.has(t)) tagMap.set(t, []);
      tagMap.get(t)!.push(p);
    }
  }
  for (const [tag, items] of [...tagMap.entries()].sort((a, b) => a[0].localeCompare(b[0]))) {
    out.push({
      id: `tag-${tag}`,
      title: `#${tag}`,
      kind: 'lined',
      count: items.length,
      lastUpdated: maxOf(items, (p) => p.last_modified),
      filter: (p) => p.tags.includes(tag),
    });
  }

  // Archive
  const archive = projects.filter((p) => p.is_archived === 1);
  out.push({
    id: 'archive',
    title: 'Archive',
    kind: 'manila',
    count: archive.length,
    lastUpdated: maxOf(archive, (p) => p.last_modified),
    filter: (p) => p.is_archived === 1,
  });

  // Claude (synthetic — populated from journal, here just a placeholder count)
  out.push({
    id: 'claude',
    title: 'Claude',
    kind: 'kraft',
    count: 0,
    lastUpdated: null,
    filter: () => false,
  });

  return out;
}

function maxOf<T>(arr: T[], pick: (t: T) => number): number | null {
  if (arr.length === 0) return null;
  return arr.reduce((m, x) => Math.max(m, pick(x)), -Infinity);
}

export function fmtDate(unix: number | null): string | undefined {
  if (unix == null) return undefined;
  return new Date(unix * 1000).toISOString().slice(0, 10);
}
