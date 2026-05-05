import { afterEach, describe, expect, test, vi } from 'vitest';

// VITE_USE_MOCKS is unset here so listProjects builds a real URL and calls
// fetch — that's the surface we're verifying for the new param.
vi.stubEnv('VITE_USE_MOCKS', 'false');

afterEach(() => {
  vi.restoreAllMocks();
});

describe('listProjects query-string for needs_attention', () => {
  test('omits the param when not provided', async () => {
    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(new Response('[]', { status: 200 }));
    const { listProjects } = await import('./api');
    await listProjects();
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const url = String(fetchSpy.mock.calls[0]![0]);
    expect(url).not.toContain('needs_attention');
  });

  test('serializes needs_attention=true', async () => {
    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(new Response('[]', { status: 200 }));
    const { listProjects } = await import('./api');
    await listProjects({ needs_attention: true });
    const url = String(fetchSpy.mock.calls[0]![0]);
    expect(url).toContain('needs_attention=true');
  });

  test('serializes needs_attention=false', async () => {
    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(new Response('[]', { status: 200 }));
    const { listProjects } = await import('./api');
    await listProjects({ needs_attention: false });
    const url = String(fetchSpy.mock.calls[0]![0]);
    expect(url).toContain('needs_attention=false');
  });
});
