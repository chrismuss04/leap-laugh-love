import { expect, Locator, Page } from '@playwright/test';

/** Header and navigation shared by every signed-in page. */
export class Shell {
  readonly nav: Locator;
  readonly liveStatus: Locator;
  readonly profileButton: Locator;
  readonly menu: Locator;
  readonly logout: Locator;

  constructor(readonly page: Page) {
    this.nav = page.getByRole('navigation', { name: 'Main' });
    this.liveStatus = page.locator('.live-status');
    this.profileButton = page.locator('.profile-button');
    this.menu = page.getByRole('menu');
    this.logout = page.getByRole('menuitem', { name: 'Log out' });
  }

  link(name: 'Dashboard' | 'Orders' | 'Holdings'): Locator {
    return this.nav.getByRole('link', { name, exact: true });
  }

  async expectSignedInAs(fullName: string): Promise<void> {
    await expect(this.profileButton).toContainText(fullName);
  }

  async signOut(): Promise<void> {
    await this.profileButton.click();
    await this.logout.click();
  }
}
