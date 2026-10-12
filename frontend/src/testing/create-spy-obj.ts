import type { MockedObject } from 'vitest';

/**
 * Vitest stand-in for jasmine.createSpyObj: an object whose named methods are vi.fn() mocks, plus
 * any plain properties. Typed as loosely as Jasmine's, so a partial mock can stand in for the
 * whole service it replaces.
 *
 * @param baseName name shown for the mocks in failure messages
 * @param methodNames methods to create as mocks
 * @param properties plain properties to copy onto the object
 * @returns the mock object
 */
export function createSpyObj<T = any>(
  baseName: string,
  methodNames: string[],
  properties: Record<string, unknown> = {}
): MockedObject<T> {
  const spyObj: Record<string, unknown> = { ...properties };
  for (const name of methodNames) {
    spyObj[name] = vi.fn().mockName(`${baseName}.${name}`);
  }
  return spyObj as MockedObject<T>;
}
