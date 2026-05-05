import { afterAll, beforeAll, describe, expect, test, vi } from 'vitest';

vi.stubEnv('VITE_USE_MOCKS', 'true');

let api: typeof import('./api');

beforeAll(async () => {
  api = await import('./api');
});

afterAll(() => {
  vi.unstubAllEnvs();
});

describe('api (mock mode)', () => {
  test('listProjects returns one paginated page', async () => {
    const page = await api.listProjects({ limit: 50 });
    expect(page.items).toHaveLength(50);
    expect(page.items[0]).toHaveProperty('name');
    expect(page.items[0]).toHaveProperty('color_tag');
    expect(page).toHaveProperty('next_cursor');
  });

  test('listAllProjects walks the cursor and returns the full list', async () => {
    const all = await api.listAllProjects({ limit: 20 });
    // Mock seeds 50 projects; walking pages of 20 should produce 50 total.
    expect(all).toHaveLength(50);
    const ids = new Set(all.map((p) => p.id));
    expect(ids.size).toBe(50); // no duplicates across pages
  });

  test('getProject returns ProjectDetail with plugins/samples arrays', async () => {
    const all = await api.listAllProjects();
    const id = all[0]!.id;
    const detail = await api.getProject(id);
    expect(detail).toBeDefined();
    expect(detail!.plugins).toBeInstanceOf(Array);
    expect(detail!.samples).toBeInstanceOf(Array);
  });

  test('listProposals returns array of new-shape proposals', async () => {
    const ps = await api.listProposals();
    expect(ps.length).toBeGreaterThan(0);
    expect(ps[0]).toHaveProperty('proposal_id');
    expect(ps[0]).toHaveProperty('actions');
    expect(Array.isArray(ps[0]!.actions)).toBe(true);
  });

  test('approveProposal returns a batch_id and removes from list', async () => {
    const before = await api.listProposals();
    const target = before[0]!;
    const res = await api.approveProposal(target.proposal_id);
    expect(res.batch_id).toMatch(/^mock-batch-/);
    const after = await api.listProposals();
    expect(after.find((p) => p.proposal_id === target.proposal_id)).toBeUndefined();
  });

  test('listJournal returns batches', async () => {
    const j = await api.listJournal();
    expect(j.length).toBeGreaterThan(0);
    expect(j[0]).toHaveProperty('batch_id');
    expect(j[0]).toHaveProperty('actions');
  });
});
