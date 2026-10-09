import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    // Jasmine removed every spyOn after each spec; Vitest keeps vi.spyOn spies installed unless
    // told to. The unit-test builder runs every spec file in one browser page (isolate: false), and
    // a setup-file afterEach is not reliably re-registered for each file, so spies on shared
    // globals (document, window.fetch, Date) leaked into later tests. Vitest applies this itself
    // before every test.
    restoreMocks: true,
  },
});
