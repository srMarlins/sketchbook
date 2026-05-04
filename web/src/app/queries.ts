import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  approveProposal,
  getProject,
  listJournal,
  listProjects,
  listProposals,
  rejectProposal,
  submitProposal,
  undoBatch,
  type ListProjectsParams,
} from '../lib/api';
import type { ProposalSubmission } from '../lib/types';

export const projectsKey = (params: ListProjectsParams = {}) =>
  ['projects', params] as const;
export const projectKey = (id: number) => ['project', id] as const;
export const proposalsKey = () => ['proposals'] as const;
export const journalKey = () => ['journal'] as const;

export const projectsQuery = (params: ListProjectsParams = {}) => ({
  queryKey: projectsKey(params),
  queryFn: () => listProjects(params),
  staleTime: 60_000,
});

export const projectQuery = (id: number) => ({
  queryKey: projectKey(id),
  queryFn: () => getProject(id),
  staleTime: 60_000,
});

export const proposalsQuery = () => ({
  queryKey: proposalsKey(),
  queryFn: () => listProposals(),
  staleTime: 15_000,
});

export const journalQuery = () => ({
  queryKey: journalKey(),
  queryFn: () => listJournal(),
  staleTime: 15_000,
});

export function useProjects(params: ListProjectsParams = {}) {
  return useQuery(projectsQuery(params));
}
export function useProject(id: number) {
  return useQuery(projectQuery(id));
}
export function useProposals() {
  return useQuery(proposalsQuery());
}
export function useJournal() {
  return useQuery(journalQuery());
}

// --- mutations ---------------------------------------------------------------
// Approve/reject/submit/undo all touch the proposals + journal + projects
// surfaces, so we invalidate broadly. The batch_id returned from approve is
// useful for surfacing toast confirmations at the call site.

export function useSubmitProposal() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: ProposalSubmission) => submitProposal(body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: proposalsKey() });
    },
  });
}

export function useApproveProposal() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (proposalId: string) => approveProposal(proposalId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: proposalsKey() });
      qc.invalidateQueries({ queryKey: journalKey() });
      qc.invalidateQueries({ queryKey: ['projects'] });
      qc.invalidateQueries({ queryKey: ['project'] });
    },
  });
}

export function useRejectProposal() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (proposalId: string) => rejectProposal(proposalId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: proposalsKey() });
    },
  });
}

export function useUndoBatch() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (batchId: string) => undoBatch(batchId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: journalKey() });
      qc.invalidateQueries({ queryKey: ['projects'] });
      qc.invalidateQueries({ queryKey: ['project'] });
    },
  });
}
