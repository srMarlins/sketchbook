import { lazy, Suspense } from 'react';
import {
  Outlet,
  RouterProvider,
  createRootRoute,
  createRoute,
  createRouter,
} from '@tanstack/react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { HomeRoute } from '../routes/index';
import { ProposalsRoute } from '../routes/proposals';
import { NotebookRoute } from '../routes/notebook';
import { RepairRoute } from '../routes/repair';
import { useIndexerCachePatcher } from '../hooks/useIndexerCachePatcher';

// Invisible component that subscribes to /api/events and patches the
// TanStack cache as scan_row / findings_changed events arrive. Lives
// inside QueryClientProvider so it has access to the cache.
function IndexerEventBridge() {
  useIndexerCachePatcher();
  return null;
}

// /_dev pulls in the entire component registry; lazy-load so it doesn't
// land in the main user bundle.
const DevRouteLazy = lazy(() =>
  import('../routes/_dev').then((m) => ({ default: m.DevRoute })),
);

const queryClient = new QueryClient();

const rootRoute = createRootRoute({
  component: () => (
    <QueryClientProvider client={queryClient}>
      <IndexerEventBridge />
      <Outlet />
    </QueryClientProvider>
  ),
});

const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  component: HomeRoute,
});

const devRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/_dev',
  component: () => (
    <Suspense fallback={<div className="p-6">loading dev viewer…</div>}>
      <DevRouteLazy />
    </Suspense>
  ),
});

const proposalsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/proposals',
  component: ProposalsRoute,
});

const notebookRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/n/$notebookId',
  component: NotebookRoute,
});

const repairRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/repair',
  component: RepairRoute,
});

const routeTree = rootRoute.addChildren([
  indexRoute,
  devRoute,
  proposalsRoute,
  notebookRoute,
  repairRoute,
]);

export const router = createRouter({ routeTree });

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router;
  }
}

export function AppRouter() {
  return <RouterProvider router={router} />;
}
