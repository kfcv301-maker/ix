import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const temporary = mkdtempSync(resolve(tmpdir(), 'lunaris-user-password-'));
after(() => {
  if (!resolve(temporary).startsWith(resolve(tmpdir()) + sep)) throw new Error('Unsafe temporary cleanup path');
  rmSync(temporary, { recursive: true, force: true });
});
execFileSync(process.execPath, [resolve(frontend, 'node_modules/typescript/bin/tsc'),
  resolve(frontend, 'src/utils/user-password.ts'), '--outDir', temporary, '--target', 'ES2020',
  '--module', 'ES2020', '--moduleResolution', 'bundler', '--strict', '--skipLibCheck',
], { cwd: frontend, stdio: 'pipe' });
writeFileSync(resolve(temporary, 'package.json'), '{"type":"module"}');
const { generateUserPassword } = await import(pathToFileURL(resolve(temporary, 'user-password.js')).href);

test('generated user passwords are 20 characters with upper, lower, digits and symbols', () => {
  const passwords = Array.from({ length: 200 }, () => generateUserPassword());
  for (const password of passwords) {
    assert.equal(password.length, 20);
    for (const pattern of [/[A-Z]/, /[a-z]/, /[0-9]/, /[!@#$%&*+\-=?]/]) assert.match(password, pattern);
    assert.doesNotMatch(password, /\s/);
  }
  assert.equal(new Set(passwords).size, passwords.length);
});

test('a failed secure random source never produces a predictable fallback password', () => {
  const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'crypto');
  Object.defineProperty(globalThis, 'crypto', { configurable: true, value: { getRandomValues() { throw new Error('unavailable'); } } });
  try { assert.throws(() => generateUserPassword(), /unavailable/); }
  finally { Object.defineProperty(globalThis, 'crypto', descriptor); }
});
