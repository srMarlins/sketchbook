import { mock } from '../mocks/handlers';
import type {
  JournalBatch,
  ProjectDetail,
  ProjectSummary,
  Proposal,
  ProposalSubmission,
} from './types';

const USE_MOCKS = import.meta.env['VITE_USE_MOCKS'] === 'true';

async function http<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init);
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`${url} → ${res.status}${text ? `: ${text}` : ''}`);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export interface ListProjectsParams {
  query?: string;
  tempo_min?: number;
  tempo_max?: number;
  archived?: boolean;
  limit?: number;
}

export async function listProjects(
  params: ListProjectsParams = {},
): Promise<ProjectSummary[]> {
  if (USE_MOCKS) return Promise.resolve(mock.listProjects());
  const qs = new URLSearchParams();
  if (params.query) qs.set('query', params.query);
  if (params.tempo_min !== undefined) qs.set('tempo_min', String(params.tempo_min));
  if (params.tempo_max !== undefined) qs.set('tempo_max', String(params.tempo_max));
  if (params.archived !== undefined) qs.set('archived', String(params.archived));
  if (params.limit !== undefined) qs.set('limit', String(params.limit));
  const tail = qs.toString() ? `?${qs}` : '';
  return http<ProjectSummary[]>(`/api/projects${tail}`);
}

export async function getProject(id: number): Promise<ProjectDetail | undefined> {
  if (USE_MOCKS) return Promise.resolve(mock.getProject(id));
  return http<ProjectDetail>(`/api/projects/${id}`);
}

export async function openProject(id: number): Promise<{ opened: string }> {
  if (USE_MOCKS) return Promise.resolve({ opened: `mock://${id}` });
  return http<{ opened: string }>(`/api/projects/${id}/open`, { method: 'POST' });
}

export async function listProposals(): Promise<Proposal[]> {
  if (USE_MOCKS) return Promise.resolve(mock.listProposals());
  return http<Proposal[]>('/api/proposals');
}

export async function getProposal(id: string): Promise<Proposal | undefined> {
  if (USE_MOCKS) return Promise.resolve(mock.getProposal(id));
  return http<Proposal>(`/api/proposals/${id}`);
}

export async function submitProposal(body: ProposalSubmission): Promise<{ proposal_id: string }> {
  if (USE_MOCKS) return Promise.resolve(mock.submitProposal(body));
  return http<{ proposal_id: string }>('/api/proposals', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });
}

export async function approveProposal(id: string): Promise<{ batch_id: string }> {
  if (USE_MOCKS) return Promise.resolve(mock.approveProposal(id));
  return http<{ batch_id: string }>(`/api/proposals/${id}/approve`, { method: 'POST' });
}

export async function rejectProposal(id: string): Promise<void> {
  if (USE_MOCKS) {
    mock.rejectProposal(id);
    return;
  }
  await http<void>(`/api/proposals/${id}`, { method: 'DELETE' });
}

export async function listJournal(): Promise<JournalBatch[]> {
  if (USE_MOCKS) return Promise.resolve(mock.listJournal());
  return http<JournalBatch[]>('/api/journal');
}

export async function getJournalBatch(batchId: string): Promise<JournalBatch> {
  if (USE_MOCKS) {
    const b = mock.listJournal().find((x) => x.batch_id === batchId);
    if (!b) throw new Error(`Batch ${batchId} not found`);
    return b;
  }
  return http<JournalBatch>(`/api/journal/${batchId}`);
}

export async function undoBatch(batchId: string): Promise<{ undone: string }> {
  if (USE_MOCKS) return Promise.resolve(mock.undoBatch(batchId));
  return http<{ undone: string }>(`/api/journal/${batchId}/undo`, { method: 'POST' });
}
