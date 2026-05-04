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

  test('getProject returns ProjectDetail with plugins/samples arrays', async () => {
    const rows = await api.listProjects();
    const id = rows[0]!.id;
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
