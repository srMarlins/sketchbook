import { expect, test } from '@playwright/test';

function allZero(durList: string): boolean {
  return durList
    .split(',')
    .map((s) => s.trim())
    .every((s) => /^(0s|0ms|0\.0s)$/.test(s) || s === 'none' || s === '');
}

test('reduced-motion disables animations on representative components', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto('/_dev');

  // Sanity: the media query must be active
  expect(
    await page.evaluate(() => matchMedia('(prefers-reduced-motion: reduce)').matches),
  ).toBe(true);

  const samples = ['Sprite', 'Button', 'SongStrip', 'NotebookPage', 'TornPagePile'];
  for (const label of samples) {
    await page.locator('aside button', { hasText: new RegExp(`^${label}$`) }).first().click();
    const durations = await page.evaluate(() => {
      const els = Array.from(document.querySelectorAll('main *')) as HTMLElement[];
      const out: { transition: string; animation: string }[] = [];
      for (const el of els.slice(0, 80)) {
        const cs = getComputedStyle(el);
        out.push({
          transition: cs.transitionDuration,
          animation: cs.animationDuration,
        });
      }
      return out;
    });
    for (const d of durations) {
      expect(allZero(d.transition), `transition: ${d.transition} (${label})`).toBe(true);
      expect(allZero(d.animation), `animation: ${d.animation} (${label})`).toBe(true);
    }
  }
});
