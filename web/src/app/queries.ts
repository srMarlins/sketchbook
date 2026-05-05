import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import {
  approveProposal,
  getCategory,
  getCategoryPage,
  getHome,
  getProject,
  listAllProjects,
  listJournal,
  listProjects,
  listProposals,
  openProject,
  rejectProposal,
  submitProposal,
  undoBatch,
  type ListProjectsParams,
  type ProjectsPage,
} from '../lib/api';
import type { ProposalSubmission } from '../lib/types';
import {
  findingsKey,
  type FindingsSummary,
} from '../hooks/useIndexerCachePatcher';

export const projectsKey = (params: ListProjectsParams = {}) =>
  ['projects', params] as const;
export const projectKey = (id: number) => ['project', id] as const;
export const categoryKey = (id: string) => ['category', id] as const;
export const proposalsKey = () => ['proposals'] as const;
export const journalKey = () => ['journal'] as const;
export const homeKey = () => ['home'] as const;

export const projectsQuery = (params: ListProjectsParams = {}) => ({
  // Auto-walks all pages via cursor. Callers expect a flat ProjectSummary[]
  // and don't think about pagination — the cursor API exists at the network
  // boundary, but the query layer aggregates so existing consumers (notebook
  // virtualization, proposal lookups) keep working unchanged.
  queryKey: projectsKey(params),
  queryFn: () => listAllProjects(params),
  staleTime: 60_000,
});

export const projectQuery = (id: number | null) => ({
  queryKey: projectKey(id ?? 0),
  queryFn: () => getProject(id as number),
  staleTime: 60_000,
  enabled: id != null,
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

export const homeQuery = () => ({
  queryKey: homeKey(),
  queryFn: () => getHome(),
  staleTime: 30_000,
});

export function useProjects(params: ListProjectsParams = {}) {
  return useQuery(projectsQuery(params));
}

/**
 * Cursor-paginated infinite-query hook. Use this when you want to render
 * pages progressively (e.g. an infinite-scroll view) instead of waiting for
 * the full library. For everyday "give me all projects" callers, prefer
 * `useProjects` which auto-walks pages and returns a flat array.
 */
export function useProjectsInfinite(params: ListProjectsParams = {}) {
  return useInfiniteQuery({
    queryKey: ['projects-infinite', params] as const,
    queryFn: ({ pageParam }) => listProjects({ ...params, cursor: pageParam }),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage: ProjectsPage) => lastPage.next_cursor,
    staleTime: 60_000,
  });
}

/** Cursor-paginated infinite-query hook for one home category. */
export function useCategoryInfinite(id: string | null | undefined) {
  return useInfiniteQuery({
    queryKey: ['category-infinite', id ?? ''] as const,
    queryFn: ({ pageParam }) =>
      getCategoryPage(id ?? '', { cursor: pageParam, limit: 200 }),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage: ProjectsPage) => lastPage.next_cursor,
    staleTime: 60_000,
    enabled: !!id,
  });
}

/**
 * Lightweight count helper for the first-launch splash. Reads the existing
 * `projects` cache (populated by `useProjects()` and invalidated on every
 * `scan_row` event by `useIndexerCachePatcher`). The server caps results at
 * 200 by default, so this saturates at 200 for very large catalogs — fine
 * for the splash's "30+ rows" auto-dismiss threshold.
 */
export function useProjectsCount(): number {
  const { data } = useProjects();
  return data?.length ?? 0;
}
export function useProject(id: number | null) {
  return useQuery(projectQuery(id));
}
export function useCategory(id: string | null | undefined) {
  return useQuery({
    queryKey: categoryKey(id ?? ''),
    queryFn: () => getCategory(id ?? ''),
    staleTime: 60_000,
    enabled: !!id,
  });
}
export function useProposals() {
  return useQuery(proposalsQuery());
}
export function useJournal() {
  return useQuery(journalQuery());
}
export function useHome() {
  return useQuery(homeQuery());
}

// Read-only view of the findings summary that `useIndexerCachePatcher`
// writes via `setQueryData`. We pass a `queryFn` that always rejects and
// keep `enabled: false` so TanStack never fetches; the entry is populated
// purely by the SSE bridge. `useQuery` still re-renders consumers when
// `setQueryData` updates the cache, which is what we want.
export function useFindings(): FindingsSummary | undefined {
  return useQuery<FindingsSummary>({
    queryKey: findingsKey,
    queryFn: () =>
      Promise.reject(new Error('findings only set by indexer events')),
    enabled: false,
    staleTime: Infinity,
  }).data;
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

export function useOpenProject() {
  return useMutation({
    mutationFn: (id: number) => openProject(id),
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
