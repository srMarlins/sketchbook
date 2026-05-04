import { expect, test } from '@playwright/test';

test('browse a year notebook, open a project, close the corkboard', async ({ page }) => {
  await page.goto('/');

  // Click the 2024 NotebookSpine
  await page.getByRole('button', { name: /^2024/ }).first().click();
  await expect(page).toHaveURL(/\/n\/year-2024$/);
  await expect(page.getByRole('heading', { name: '2024' })).toBeVisible();

  // Click the first SongStrip → corkboard slides in.
  // Strips live inside the virtualizer scroll container; pick a button there.
  const firstStrip = page.getByTestId('song-strip').first();
  await firstStrip.waitFor({ state: 'visible' });
  await firstStrip.click();

  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByRole('button', { name: /close panel/i })).toBeVisible();

  // Esc closes the corkboard
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toBeHidden();
});
