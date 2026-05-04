import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from '@tanstack/react-router';
import { HomeRoute } from '../routes/index';
import { DevRoute } from '../routes/_dev';

function renderAt(path: string) {
  const rootRoute = createRootRoute();
  const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: HomeRoute });
  const devRoute = createRoute({ getParentRoute: () => rootRoute, path: '/_dev', component: DevRoute });
  const router = createRouter({
    routeTree: rootRoute.addChildren([indexRoute, devRoute]),
    history: createMemoryHistory({ initialEntries: [path] }),
  });
  render(<RouterProvider router={router} />);
}

describe('routing', () => {
  test('/ renders home stub', async () => {
    renderAt('/');
    expect(await screen.findByText('home stub')).toBeInTheDocument();
  });

  test('/_dev renders dev stub', async () => {
    renderAt('/_dev');
    expect(await screen.findByText('dev stub')).toBeInTheDocument();
  });
});
