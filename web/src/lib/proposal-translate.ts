/**
 * Translate a backend ProposedAction (or batched group) into the verb/before/after
 * shape the UI used to read directly. Single source of truth for action verbiage —
 * if the backend gains a new ActionType, add it here and TS will surface every UI
 * site that needs an update.
 */
import type {
  JournalAction,
  ProjectSummary,
  ProposedAction,
  Proposal,
} from './types';

export type Verb = 'rename' | 'move' | 'archive' | 'color' | 'tag' | 'repair' | 'relink';

export interface TranslatedAction {
  verb: Verb;
  /** Project this action targets, or undefined if it doesn't apply to one. */
  project_id: number;
  /** Short label used in card headers — e.g., "rename" → "demos (rev)". */
  label: string;
  before: string;
  after: string;
}

const VERB_BY_TYPE: Record<ProposedAction['type'], Verb> = {
  RenameProject: 'rename',
  MoveProject: 'move',
  ArchiveProject: 'archive',
  SetColorTag: 'color',
  SetTags: 'tag',
  RepairMacPaths: 'repair',
  RelinkMissingSamples: 'relink',
};

/** Translate a single proposed action against a known project (for before-state). */
export function translateProposed(
  action: ProposedAction,
  project?: ProjectSummary,
): TranslatedAction {
  const verb = VERB_BY_TYPE[action.type];
  const project_id = action.args.project_id;
  switch (action.type) {
    case 'RenameProject':
      return {
        verb,
        project_id,
        label: action.args.new_dir_name,
        before: project?.name ?? '—',
        after: action.args.new_dir_name,
      };
    case 'MoveProject':
      return {
        verb,
        project_id,
        label: action.args.new_parent,
        before: project?.parent_dir ?? '—',
        after: action.args.new_parent,
      };
    case 'ArchiveProject':
      return {
        verb,
        project_id,
        label: project?.name ?? `#${project_id}`,
        before: 'active',
        after: 'archived',
      };
    case 'SetColorTag':
      return {
        verb,
        project_id,
        label: action.args.color === null ? 'cleared' : `als-${action.args.color + 1}`,
        before:
          project?.color_tag === null || project?.color_tag === undefined
            ? '—'
            : `als-${project.color_tag + 1}`,
        after: action.args.color === null ? '—' : `als-${action.args.color + 1}`,
      };
    case 'SetTags':
      return {
        verb,
        project_id,
        label: action.args.tags.join(', ') || '(none)',
        before: project?.tags.join(', ') || '—',
        after: action.args.tags.join(', ') || '—',
      };
    case 'RepairMacPaths':
      return {
        verb,
        project_id,
        label: project?.name ?? `#${project_id}`,
        before: 'mac-imported',
        after: 'repaired',
      };
    case 'RelinkMissingSamples': {
      const n = action.args.relinks.length;
      return {
        verb,
        project_id,
        label: `${n} relink${n === 1 ? '' : 's'}`,
        before: 'missing',
        after: 'linked',
      };
    }
  }
}

/** Translate a journal action (already-executed; before/after are recorded). */
export function translateJournal(action: JournalAction): TranslatedAction {
  switch (action.type) {
    case 'RenameProject':
      return {
        verb: 'rename',
        project_id: action.project_id,
        label: action.to,
        before: action.from_,
        after: action.to,
      };
    case 'MoveProject':
      return {
        verb: 'move',
        project_id: action.project_id,
        label: action.to,
        before: action.from_,
        after: action.to,
      };
    case 'ArchiveProject':
      return {
        verb: 'archive',
        project_id: action.project_id,
        label: action.to,
        before: action.from_,
        after: action.to,
      };
    case 'SetColorTag':
      return {
        verb: 'color',
        project_id: action.project_id,
        label: action.after === null ? 'cleared' : `als-${action.after + 1}`,
        before: action.before === null ? '—' : `als-${action.before + 1}`,
        after: action.after === null ? '—' : `als-${action.after + 1}`,
      };
    case 'SetTags':
      return {
        verb: 'tag',
        project_id: action.project_id,
        label: action.after.join(', ') || '(none)',
        before: action.before.join(', ') || '—',
        after: action.after.join(', ') || '—',
      };
  }
}

/** Translate the first action of a Proposal and report whether more were elided. */
export function translateProposalHead(
  proposal: Proposal,
  project?: ProjectSummary,
): TranslatedAction & { extra: number } {
  if (proposal.actions.length === 0) {
    return {
      verb: 'tag',
      project_id: 0,
      label: '(empty proposal)',
      before: '—',
      after: '—',
      extra: 0,
    };
  }
  const head = translateProposed(proposal.actions[0]!, project);
  return { ...head, extra: proposal.actions.length - 1 };
}
