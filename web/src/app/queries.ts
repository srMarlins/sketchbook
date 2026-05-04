import { useQuery } from '@tanstack/react-query';
import {
  listJournal,
  listProjects,
  listProposals,
  listSuggestions,
} from '../lib/api';

export const projectsQuery = () => ({
  queryKey: ['projects'] as const,
  queryFn: () => listProjects(),
  staleTime: 60_000,
});
export const proposalsQuery = () => ({
  queryKey: ['proposals'] as const,
  queryFn: () => listProposals(),
  staleTime: 30_000,
});
export const journalQuery = (projectId?: number) => ({
  queryKey: projectId === undefined ? (['journal'] as const) : (['journal', projectId] as const),
  queryFn: () => listJournal(projectId),
  staleTime: 30_000,
});
export const suggestionsQuery = () => ({
  queryKey: ['suggestions'] as const,
  queryFn: () => listSuggestions(),
  staleTime: 60_000,
});

export function useProjects() {
  return useQuery(projectsQuery());
}
export function useProposals() {
  return useQuery(proposalsQuery());
}
export function useJournal(projectId?: number) {
  return useQuery(journalQuery(projectId));
}
export function useSuggestions() {
  return useQuery(suggestionsQuery());
}
