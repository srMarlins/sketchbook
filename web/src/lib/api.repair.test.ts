import { afterEach, describe, expect, test, vi } from 'vitest';

vi.stubEnv('VITE_USE_MOCKS', 'false');

afterEach(() => {
  vi.restoreAllMocks();
});

describe('fetchRepairFindings', () => {
  test('returns mac + missing-sample findings, snake → camel', async () => {
    const body = {
      mac_imports: [
        {
          project_id: 1,
          path: '/p.als',
          name: 'p',
          parent_dir: '/',
          mac_paths_count: 3,
          project_info_missing: false,
        },
      ],
      missing_samples: [
        {
          project_id: 1,
          project_path: '/p.als',
          project_name: 'p',
          missing_path: 'k.wav',
          auto_match: { path: '/lib/k.wav', filename: 'k.wav', size_bytes: 1 },
          candidates: [],
        },
      ],
    };
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify(body), { status: 200 }),
    );
    const { fetchRepairFindings } = await import('./api');
    const data = await fetchRepairFindings();
    expect(data.macImports).toHaveLength(1);
    expect(data.macImports[0]!.macPathsCount).toBe(3);
    expect(data.missingSamples[0]!.autoMatch?.path).toBe('/lib/k.wav');
    expect(data.missingSamples[0]!.autoMatch?.sizeBytes).toBe(1);
  });

  test('handles empty findings', async () => {
    const body = { mac_imports: [], missing_samples: [] };
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify(body), { status: 200 }),
    );
    const { fetchRepairFindings } = await import('./api');
    const data = await fetchRepairFindings();
    expect(data.macImports).toEqual([]);
    expect(data.missingSamples).toEqual([]);
  });
});
