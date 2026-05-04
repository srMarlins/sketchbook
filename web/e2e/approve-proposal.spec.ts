import { expect, test } from '@playwright/test';

test('approve a proposal removes it from the pile', async ({ page }) => {
  await page.goto('/proposals');
  await expect(page.getByRole('heading', { name: 'Proposals' })).toBeVisible();

  const before = await page.locator('[data-testid="proposal-card"]').count();
  expect(before).toBeGreaterThan(0);

  // Use exact match so we don't hit "approve all"
  await page.getByRole('button', { name: 'approve', exact: true }).first().click();

  // Wait for the pile to reflect the change.
  await expect.poll(async () => page.locator('[data-testid="proposal-card"]').count()).toBe(before - 1);
});
