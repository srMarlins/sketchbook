import {
  Outlet,
  RouterProvider,
  createRootRoute,
  createRoute,
  createRouter,
} from '@tanstack/react-router';
import { HomeRoute } from '../routes/index';
import { DevRoute } from '../routes/_dev';

const rootRoute = createRootRoute({
  component: () => (
    <div className="min-h-screen bg-surface-page text-ink-primary">
      <Outlet />
    </div>
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

const routeTree = rootRoute.addChildren([indexRoute, devRoute]);

export const router = createRouter({ routeTree });

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router;
  }
}

export function AppRouter() {
  return <RouterProvider router={router} />;
}
