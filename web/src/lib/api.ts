import { mock } from '../mocks/handlers';
import type { JournalEntry, Project, Proposal, Suggestion } from './types';

const USE_MOCKS = import.meta.env['VITE_USE_MOCKS'] === 'true';

async function networkJson<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init);
  if (!res.ok) throw new Error(`${url} → ${res.status}`);
  return (await res.json()) as T;
}

export async function listProjects(): Promise<Project[]> {
  if (USE_MOCKS) return Promise.resolve(mock.listProjects());
  return networkJson<Project[]>('/api/projects');
}

export async function getProject(id: number): Promise<Project | undefined> {
  if (USE_MOCKS) return Promise.resolve(mock.getProject(id));
  return networkJson<Project>(`/api/projects/${id}`);
}

export async function listProposals(): Promise<Proposal[]> {
  if (USE_MOCKS) return Promise.resolve(mock.listProposals());
  return networkJson<Proposal[]>('/api/proposals');
}

export async function approveProposal(id: string): Promise<Proposal> {
  if (USE_MOCKS) return Promise.resolve(mock.approveProposal(id));
  return networkJson<Proposal>(`/api/proposals/${id}/approve`, { method: 'POST' });
}

export async function rejectProposal(id: string): Promise<Proposal> {
  if (USE_MOCKS) return Promise.resolve(mock.rejectProposal(id));
  return networkJson<Proposal>(`/api/proposals/${id}/reject`, { method: 'POST' });
}

export async function listJournal(projectId?: number): Promise<JournalEntry[]> {
  if (USE_MOCKS) return Promise.resolve(mock.listJournal(projectId));
  const qs = projectId !== undefined ? `?project_id=${projectId}` : '';
  return networkJson<JournalEntry[]>(`/api/journal${qs}`);
}

export async function listSuggestions(): Promise<Suggestion[]> {
  if (USE_MOCKS) return Promise.resolve(mock.listSuggestions());
  return networkJson<Suggestion[]>('/api/suggestions');
}
