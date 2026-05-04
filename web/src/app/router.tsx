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

// /_dev pulls in the entire component registry; lazy-load so it doesn't
// land in the main user bundle.
const DevRouteLazy = lazy(() =>
  import('../routes/_dev').then((m) => ({ default: m.DevRoute })),
);

const queryClient = new QueryClient();

const rootRoute = createRootRoute({
  component: () => (
    <QueryClientProvider client={queryClient}>
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

const routeTree = rootRoute.addChildren([indexRoute, devRoute, proposalsRoute, notebookRoute]);

export const router = createRouter({ routeTree });

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router;
  }
}

export function AppRouter() {
  return <RouterProvider router={router} />;
}
