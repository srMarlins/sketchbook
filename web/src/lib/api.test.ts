import { beforeAll, describe, expect, test, vi } from 'vitest';

vi.stubEnv('VITE_USE_MOCKS', 'true');

let api: typeof import('./api');

beforeAll(async () => {
  api = await import('./api');
});

describe('api (mock mode)', () => {
  test('listProjects returns 50 fixture rows', async () => {
    const rows = await api.listProjects();
    expect(rows).toHaveLength(50);
    expect(rows[0]).toHaveProperty('name');
    expect(rows[0]).toHaveProperty('color_tag');
  });

  test('listProposals returns 8 pending proposals', async () => {
    const ps = await api.listProposals();
    expect(ps).toHaveLength(8);
  });

  test('listJournal returns 20 entries when unfiltered', async () => {
    const j = await api.listJournal();
    expect(j).toHaveLength(20);
  });

  test('approveProposal flips status to approved', async () => {
    const all = await api.listProposals();
    const target = all[0]!;
    const res = await api.approveProposal(target.id);
    expect(res.status).toBe('approved');
  });
});
