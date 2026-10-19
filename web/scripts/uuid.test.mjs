import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createUuid } from '../src/lib/uuid.ts';

test('generates a UUID v4 when randomUUID is unavailable', () => {
  const originalCrypto = globalThis.crypto;
  Object.defineProperty(globalThis, 'crypto', {
    configurable: true,
    value: { getRandomValues: bytes => { bytes.fill(0); return bytes; } },
  });
  try {
    const value = createUuid();
    assert.match(value, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  } finally {
    Object.defineProperty(globalThis, 'crypto', { configurable: true, value: originalCrypto });
  }
});
