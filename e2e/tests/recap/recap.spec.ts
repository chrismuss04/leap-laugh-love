import { test, expect } from '../../fixtures/test';
import { Shell } from '../../pages/shell';
import { DashboardPage } from '../../pages/dashboard.page';
import { lastRecapMonthName, RecapPage } from '../../pages/recap.page';

test.use({ persona: 'alice' });

test.describe('Monthly recap', () => {
  let shell: Shell;
  let recap: RecapPage;

  test.beforeEach(async ({ page }) => {
    shell = new Shell(page);
    recap = new RecapPage(page);
    await new DashboardPage(page).goto();
  });

  test('opens last month’s recap from the nav @smoke', async ({ page }) => {
    const month = lastRecapMonthName();

    await shell.recapLink.click();

    await expect(page).toHaveURL(/\/recap$/);
    await recap.loaded();
    await expect(recap.error).toBeHidden();

    // Alice's seeded orders are dated relative to when the database was seeded, so whether last
    // month has anything in it depends on the day the suite runs: the story ("Your September,
    // recapped.") or its empty note ("Nothing to recap for September"). Either way, that month's.
    await expect(recap.stage.or(recap.empty)).toContainText(month);
  });

  test('marks the Recap link new until last month’s recap is opened', async () => {
    // A fresh browser has never opened a recap.
    await expect(shell.recapLink).toHaveClass(/is-new/);
    await expect(shell.recapLink).toHaveAccessibleName('Recap (new)');

    await shell.recapLink.click();
    await recap.loaded();

    await expect(shell.recapLink).not.toHaveClass(/is-new/);
    await expect(shell.recapLink).toHaveAccessibleName('Recap');
  });
});
