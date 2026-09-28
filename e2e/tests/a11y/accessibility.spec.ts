import AxeBuilder from '@axe-core/playwright';
import { Page } from '@playwright/test';
import { test, expect } from '../../fixtures/test';
import { DashboardPage } from '../../pages/dashboard.page';
import { OrderHistoryPage } from '../../pages/order-history.page';
import { HoldingsPage } from '../../pages/holdings.page';
import { RegistrationPage } from '../../pages/registration.page';
import { SignInPage } from '../../pages/sign-in.page';

/**
 * Known violations, each with the reason it is tolerated for now. They are still scanned for and
 * listed on the test as annotations - they just don't fail the build. Add to this only with a
 * ticket; the point of the scan is that the list shrinks.
 */
const KNOWN: Record<string, string> = {
  'color-contrast':
    'Muted text tokens (labels, nav links, hints) fall below 4.5:1 on every page - a design-token fix, not per-page.'
};

async function scan(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  // Serious and critical block unless already known; everything else is reported on the test.
  const blocking = results.violations.filter(v => (v.impact === 'serious' || v.impact === 'critical') && !(v.id in KNOWN));
  test.info().annotations.push(
    ...results.violations
      .filter(v => !blocking.includes(v))
      .map(v => ({ type: `a11y ${v.impact}`, description: `${v.id}: ${v.help} (${v.nodes.length})` }))
  );
  return blocking.map(v => ({ rule: v.id, impact: v.impact, help: v.help, targets: v.nodes.map(n => n.target.join(' ')) }));
}

test.describe('Accessibility (WCAG 2.1 AA)', () => {
  test('sign in', async ({ page }) => {
    await new SignInPage(page).goto();
    expect(await scan(page)).toEqual([]);
  });

  test('create account, including error states', async ({ page }) => {
    const form = new RegistrationPage(page);
    await form.goto();
    await form.submit.click();
    await expect(form.error).toBeVisible();
    expect(await scan(page)).toEqual([]);
  });

  test.describe('signed in', () => {
    test.use({ persona: 'alice' });

    test('dashboard', async ({ page }) => {
      await new DashboardPage(page).goto();
      expect(await scan(page)).toEqual([]);
    });

    test('dashboard with search results open', async ({ page }) => {
      const dashboard = new DashboardPage(page);
      await dashboard.goto();
      await dashboard.search.input.fill('A');
      await expect(dashboard.search.options.first()).toBeVisible();
      expect(await scan(page)).toEqual([]);
    });

    test('order history', async ({ page }) => {
      await new OrderHistoryPage(page).goto();
      expect(await scan(page)).toEqual([]);
    });

    test('holdings', async ({ page }) => {
      await new HoldingsPage(page).goto();
      expect(await scan(page)).toEqual([]);
    });
  });
});
