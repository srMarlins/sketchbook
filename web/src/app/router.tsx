import {
  Outlet,
  RouterProvider,
  createRootRoute,
  createRoute,
  createRouter,
} from '@tanstack/react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { HomeRoute } from '../routes/index';
import { DevRoute } from '../routes/_dev';
import { ProposalsRoute } from '../routes/proposals';
import { NotebookRoute } from '../routes/notebook';

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
  component: DevRoute,
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
