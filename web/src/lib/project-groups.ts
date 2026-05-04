import type { ProjectSummary } from './types';

/**
 * Many Ableton "projects" are really folders containing multiple `.als` files
 * (e.g. mix_01.als, mix_02.als, final.als). The catalog stores one row per
 * .als file. The UI collapses these into one group per `parent_dir`, with the
 * variants surfaced in the detail view.
 */
export interface ProjectGroup {
  /** Stable identifier for the group (the parent_dir itself). */
  id: string;
  parent_dir: string;
  /** basename(parent_dir), with a trailing " Project" stripped if present. */
  title: string;
  /** All .als files sharing parent_dir, sorted by mtime desc. */
  variants: ProjectSummary[];
  /**
   * The "best" variant to show as the headline. Newest non-archived; falls
   * back to newest overall if all variants are archived.
   */
  representative: ProjectSummary;
  /** Max effort_score across variants. */
  effort_score: number | null;
  /** Newest variant mtime. */
  latest_mtime: number;
  /** "Loudest" color across variants — see priority table. null if none. */
  state_color: number | null;
  /** Track count from the representative variant (variants are usually the same song). */
  total_tracks: number | null;
  variant_count: number;
}

/**
 * Ableton palette colors mapped to a subjective "loudness" priority. The first
 * match found in this order wins for the group's state_color. The numbers are
 * Ableton palette indices (0..13). null = untriaged.
 */
const COLOR_PRIORITY: number[] = [
  4, // green
  3, // yellow
  2, // orange
  5, // blue
  6, // purple
  1, // red
];

function basename(p: string): string {
  // Handle both / and \ separators (Windows paths in this codebase).
  const idx = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
  return idx >= 0 ? p.slice(idx + 1) : p;
}

function deriveTitle(parentDir: string): string {
  let t = basename(parentDir);
  // Strip trailing " Project" (case-insensitive) — Ableton's default suffix.
  t = t.replace(/\s+Project$/i, '');
  return t;
}

function pickStateColor(variants: ProjectSummary[]): number | null {
  const present = new Set<number>();
  for (const v of variants) {
    if (v.color_tag != null) present.add(v.color_tag);
  }
  for (const c of COLOR_PRIORITY) {
    if (present.has(c)) return c;
  }
  // Fall through to any other present color (e.g. palette > 6) before null.
  for (const v of variants) {
    if (v.color_tag != null) return v.color_tag;
  }
  return null;
}

export function deriveProjectGroups(projects: ProjectSummary[]): ProjectGroup[] {
  const byDir = new Map<string, ProjectSummary[]>();
  for (const p of projects) {
    const key = p.parent_dir;
    if (!byDir.has(key)) byDir.set(key, []);
    byDir.get(key)!.push(p);
  }

  const groups: ProjectGroup[] = [];
  for (const [parent_dir, list] of byDir.entries()) {
    const variants = [...list].sort((a, b) => b.last_modified - a.last_modified);
    const nonArchived = variants.filter((v) => v.is_archived === 0);
    const representative = nonArchived[0] ?? variants[0]!;
    const latest_mtime = variants[0]!.last_modified;

    let effort: number | null = null;
    for (const v of variants) {
      if (v.effort_score == null) continue;
      effort = effort == null ? v.effort_score : Math.max(effort, v.effort_score);
    }

    groups.push({
      id: parent_dir,
      parent_dir,
      title: deriveTitle(parent_dir),
      variants,
      representative,
      effort_score: effort,
      latest_mtime,
      state_color: pickStateColor(variants),
      total_tracks: representative.track_count,
      variant_count: variants.length,
    });
  }

  groups.sort((a, b) => b.latest_mtime - a.latest_mtime);
  return groups;
}
