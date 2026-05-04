import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  fullyParallel: false,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    // E2E uses the mock-mode dev server so tests don't depend on a running
    // FastAPI process. The real-backend smoke is in scripts/smoke-real.ts.
    command: 'npm run dev:mocks',
    port: 5173,
    reuseExistingServer: true,
    timeout: 60_000,
  },
});
