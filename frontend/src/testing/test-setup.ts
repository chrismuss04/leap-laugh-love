// Runs Vitest's describe/it/beforeEach bodies inside zone.js's ProxyZone, which fakeAsync and
// waitForAsync need. zone.js/testing (added by the unit-test builder) only patches Jasmine, Jest
// and Mocha, so Vitest needs this patch on top of it.
import 'zone.js/plugins/vitest-patch';
