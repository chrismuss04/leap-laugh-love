import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { InactiveNoticeComponent } from './inactive-notice';
import { AccountView } from '../models';

const account = (accountId: string, inactiveSince: string | null) =>
  ({ accountId, name: `Brokerage ··${accountId}`, inactiveSince }) as AccountView;

describe('InactiveNoticeComponent', () => {
  const render = (accounts: AccountView[]) => {
    TestBed.configureTestingModule({ imports: [InactiveNoticeComponent], providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(InactiveNoticeComponent);
    fixture.componentRef.setInput('accounts', accounts);
    fixture.detectChanges();
    return fixture;
  };
  const link = (fixture: ReturnType<typeof render>) => fixture.nativeElement.querySelector('a') as HTMLAnchorElement | null;

  it('is hidden when no account is inactive', () => {
    expect(link(render([account('1111', null)]))).toBeNull();
  });

  it('names a single inactive account and links to it', () => {
    const notice = link(render([account('1111', null), account('2222', '2026-08-12T09:00:00Z')]))!;
    expect(notice.textContent).toContain('Brokerage ··2222 needs attention: no balance since Aug 12, 2026.');
    expect(notice.getAttribute('href')).toBe('/dashboard/2222');
  });

  it('counts several inactive accounts and links to the first', () => {
    const notice = link(render([account('1111', '2026-08-01T00:00:00Z'), account('2222', '2026-08-12T00:00:00Z')]))!;
    expect(notice.textContent).toContain('2 accounts need attention.');
    expect(notice.getAttribute('href')).toBe('/dashboard/1111');
  });

  it('hides when the client closes it', () => {
    const fixture = render([account('2222', '2026-08-12T00:00:00Z')]);
    fixture.nativeElement.querySelector('button[aria-label="Dismiss"]').click();
    fixture.detectChanges();
    expect(link(fixture)).toBeNull();
  });
});
