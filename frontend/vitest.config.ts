import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    // Jasmine removed every spyOn after each spec; Vitest keeps vi.spyOn spies installed unless
    // told to. The unit-test builder runs every spec file in one browser page (isolate: false), and
    // a setup-file afterEach is not reliably re-registered for each file, so spies on shared
    // globals (document, window.fetch, Date) leaked into later tests. Vitest applies this itself
    // before every test.
    restoreMocks: true,
    // CI runs the browser alongside Docker image builds on a shared agent. Tests that only
    // navigate a router or draw a tiny component then occasionally stall past the 15 s default
    // (the pipeline log shows "Failed to take a screenshot ... Timeout" in the same window).
    testTimeout: 30_000,
  },
});
