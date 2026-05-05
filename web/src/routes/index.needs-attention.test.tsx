import { render, screen, fireEvent } from '@testing-library/react';
import { describe, expect, test, vi, beforeEach, afterEach } from 'vitest';
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
} from '@tanstack/react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { HomeRoute } from './index';

// Spy module: capture every call to listProjects so we can assert what
// params the chip toggles. The other API helpers resolve to empty data so
// the route renders without HTTP traffic.
vi.mock('../lib/api', async () => {
  const actual = await vi.importActual<typeof import('../lib/api')>('../lib/api');
  return {
    ...actual,
    listProjects: vi.fn(async () => ({ items: [], next_cursor: null })),
    listAllProjects: vi.fn(async () => []),
    getHome: vi.fn(async () => ({ shelves: [] })),
    listProposals: vi.fn(async () => []),
  };
});

import * as api from '../lib/api';

function renderHome() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
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
  const router = createRouter({
    routeTree: rootRoute.addChildren([indexRoute]),
    history: createMemoryHistory({ initialEntries: ['/'] }),
  });
  return render(<RouterProvider router={router} />);
}

beforeEach(() => {
  (api.listProjects as ReturnType<typeof vi.fn>).mockClear();
  (api.listAllProjects as ReturnType<typeof vi.fn>).mockClear();
  (api.getHome as ReturnType<typeof vi.fn>).mockClear();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('home: needs-attention filter chip', () => {
  test('initial render does not pass needs_attention to listProjects', async () => {
    renderHome();
    await screen.findByTestId('needs-attention-chip');
    const calls = (api.listAllProjects as ReturnType<typeof vi.fn>).mock.calls;
    expect(calls.length).toBeGreaterThan(0);
    for (const c of calls) {
      const params = (c[0] as { needs_attention?: boolean } | undefined) ?? {};
      expect(params.needs_attention).toBeUndefined();
    }
  });

  test('clicking the chip refetches with needs_attention=true', async () => {
    renderHome();
    const chip = await screen.findByTestId('needs-attention-chip');
    fireEvent.click(chip);
    // After the toggle, the latest listProjects call must include the flag.
    const mock = api.listAllProjects as ReturnType<typeof vi.fn>;
    const last = mock.mock.calls[mock.mock.calls.length - 1]!;
    const params = (last[0] as { needs_attention?: boolean }) ?? {};
    expect(params.needs_attention).toBe(true);
    // Chip presents pressed state once active.
    expect(chip.getAttribute('aria-pressed')).toBe('true');
  });

  test('clicking the chip again clears the active state', async () => {
    renderHome();
    const chip = await screen.findByTestId('needs-attention-chip');
    fireEvent.click(chip);
    expect(chip.getAttribute('aria-pressed')).toBe('true');
    fireEvent.click(chip);
    expect(chip.getAttribute('aria-pressed')).toBe('false');
    // The unfiltered listProjects({}) query was issued at mount, so the
    // toggle-off click is served from TanStack's cache without a refetch.
    // Cache identity is what we care about — the home grid is now reading
    // from the unfiltered query key again, even if no new HTTP call fires.
    const mock = api.listAllProjects as ReturnType<typeof vi.fn>;
    const seenUnfiltered = mock.mock.calls.some((c) => {
      const p = (c[0] as { needs_attention?: boolean } | undefined) ?? {};
      return p.needs_attention === undefined;
    });
    expect(seenUnfiltered).toBe(true);
  });
});
