import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

test.describe('axe sweep on /_dev', () => {
  for (const theme of ['light', 'dark'] as const) {
    test(`no violations in ${theme} theme`, async ({ page }) => {
      await page.goto('/_dev');
      // Click the theme toggle
      await page.getByRole('button', { name: theme, exact: true }).click();
      const results = await new AxeBuilder({ page })
        .disableRules(['color-contrast']) // strips and tokens have many sample swatches; per-component check is in unit tests
        .analyze();
      expect(
        results.violations,
        results.violations
          .map((v) => `${v.id}: ${v.help}`)
          .join('\n'),
      ).toEqual([]);
    });
  }
});
