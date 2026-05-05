import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

// jsdom doesn't supply EventSource. Tests that exercise SSE behaviour install
// their own FakeEventSource over `globalThis.EventSource`; tests that merely
// render components which subscribe to /api/events get this no-op stub so
// they don't crash with "EventSource is not defined".
class NoopEventSource {
  url: string;
  constructor(url: string) {
    this.url = url;
  }
  addEventListener() {}
  removeEventListener() {}
  close() {}
}
if (!(globalThis as unknown as { EventSource?: unknown }).EventSource) {
  (globalThis as unknown as { EventSource: typeof NoopEventSource }).EventSource =
    NoopEventSource;
}

afterEach(() => {
  cleanup();
});
