// Runs Vitest's describe/it/beforeEach bodies inside zone.js's ProxyZone, which fakeAsync and
// waitForAsync need. zone.js/testing (added by the unit-test builder) only patches Jasmine, Jest
// and Mocha, so Vitest needs this patch on top of it.
import 'zone.js/plugins/vitest-patch';

// Jasmine removed every spyOn after each spec; Vitest keeps vi.spyOn spies installed unless told
// to. Without this, spies on shared globals (document, window.fetch, Date) pile up across tests.
afterEach(() => {
  vi.restoreAllMocks();
});
