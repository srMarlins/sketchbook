import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
} from '@tanstack/react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { HomeRoute } from '../routes/index';
import { DevRoute } from '../routes/_dev';

function renderAt(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const rootRoute = createRootRoute({
    component: () => (
      <QueryClientProvider client={queryClient}>
        <Outlet />
      </QueryClientProvider>
    ),
  });
  const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: HomeRoute });
  const devRoute = createRoute({ getParentRoute: () => rootRoute, path: '/_dev', component: DevRoute });
  const router = createRouter({
    routeTree: rootRoute.addChildren([indexRoute, devRoute]),
    history: createMemoryHistory({ initialEntries: [path] }),
  });
  render(<RouterProvider router={router} />);
}

describe('routing', () => {
  test('/ renders the home shell with sidebar nav', async () => {
    renderAt('/');
    expect(await screen.findByRole('navigation', { name: /notebook sections/i })).toBeInTheDocument();
  });

  test('/_dev renders the component viewer', async () => {
    renderAt('/_dev');
    expect(
      await screen.findByRole('heading', { level: 1, name: /sketchbook component viewer/i }),
    ).toBeInTheDocument();
  });
});
