import projectsJson from './projects.json';
import proposalsJson from './proposals.json';
import journalJson from './journal.json';
import suggestionsJson from './suggestions.json';
import type { JournalEntry, Project, Proposal, Suggestion } from '../lib/types';

const projectsState: Project[] = JSON.parse(JSON.stringify(projectsJson));
const proposalsState: Proposal[] = JSON.parse(JSON.stringify(proposalsJson));
const journalState: JournalEntry[] = JSON.parse(JSON.stringify(journalJson));
const suggestionsState: Suggestion[] = JSON.parse(JSON.stringify(suggestionsJson));

export const mock = {
  listProjects(): Project[] {
    return projectsState;
  },
  getProject(id: number): Project | undefined {
    return projectsState.find((p) => p.id === id);
  },
  listProposals(): Proposal[] {
    return proposalsState;
  },
  approveProposal(id: string): Proposal {
    const idx = proposalsState.findIndex((p) => p.id === id);
    if (idx < 0) throw new Error(`Proposal ${id} not found`);
    const updated: Proposal = { ...proposalsState[idx]!, status: 'approved' };
    proposalsState[idx] = updated;
    return updated;
  },
  rejectProposal(id: string): Proposal {
    const idx = proposalsState.findIndex((p) => p.id === id);
    if (idx < 0) throw new Error(`Proposal ${id} not found`);
    const updated: Proposal = { ...proposalsState[idx]!, status: 'rejected' };
    proposalsState[idx] = updated;
    return updated;
  },
  listJournal(projectId?: number): JournalEntry[] {
    if (projectId === undefined) return journalState;
    return journalState.filter((j) => j.project_id === projectId);
  },
  listSuggestions(): Suggestion[] {
    return suggestionsState;
  },
};
