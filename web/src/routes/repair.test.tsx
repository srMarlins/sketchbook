import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RepairRoute } from './repair';

vi.stubEnv('VITE_USE_MOCKS', 'false');

vi.mock('@tanstack/react-router', async () => {
  const actual = await vi.importActual<typeof import('@tanstack/react-router')>(
    '@tanstack/react-router',
  );
  return {
    ...actual,
    useNavigate: () => () => undefined,
  };
});

afterEach(() => {
  vi.restoreAllMocks();
});

function renderWithClient(ui: React.ReactElement) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{ui}</QueryClientProvider>);
}

function mockFindings(body: object) {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(JSON.stringify(body), { status: 200 }),
  );
}

describe('/repair route', () => {
  it('renders both shelves with counts', async () => {
    mockFindings({
      mac_imports: [
        {
          project_id: 1,
          path: '/a.als',
          name: 'a',
          parent_dir: '/',
          mac_paths_count: 3,
          project_info_missing: false,
        },
      ],
      missing_samples: [
        {
          project_id: 2,
          project_path: '/b.als',
          project_name: 'b',
          missing_path: 'k.wav',
          auto_match: { path: '/lib/k.wav', filename: 'k.wav', size_bytes: 1 },
          candidates: [],
        },
      ],
    });
    renderWithClient(<RepairRoute />);
    expect(await screen.findByText(/Mac imports · 1/i)).toBeInTheDocument();
    expect(screen.getByText(/Missing samples · 1/i)).toBeInTheDocument();
  });

  it('disables rows that need review', async () => {
    mockFindings({
      mac_imports: [],
      missing_samples: [
        {
          project_id: 5,
          project_path: '/c.als',
          project_name: 'c',
          missing_path: 'x.wav',
          auto_match: null,
          candidates: [
            { path: '/a/x.wav', filename: 'x.wav', size_bytes: 1 },
            { path: '/b/x.wav', filename: 'x.wav', size_bytes: 2 },
          ],
        },
      ],
    });
    renderWithClient(<RepairRoute />);
    const row = await screen.findByTestId('missing-row-5');
    const cb = within(row).getByRole('checkbox');
    await waitFor(() => expect(cb).toBeDisabled());
    expect(within(row).getByText(/needs review/i)).toBeInTheDocument();
  });
});
