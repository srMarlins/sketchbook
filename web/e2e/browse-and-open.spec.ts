import { expect, test } from '@playwright/test';

test('browse a year notebook, open a project, close the corkboard', async ({ page }) => {
  await page.goto('/');

  // Click the 2024 spine
  await page.getByRole('button', { name: /^2024 / }).first().click();
  await expect(page).toHaveURL(/\/n\/year-2024$/);
  await expect(page.getByRole('heading', { name: '2024' })).toBeVisible();

  // Click the first SongStrip → corkboard slides in
  const firstStrip = page.locator('[data-color-idx]').first();
  await firstStrip.click();

  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByRole('button', { name: /close panel/i })).toBeVisible();

  // Esc closes the corkboard
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toBeHidden();
});
