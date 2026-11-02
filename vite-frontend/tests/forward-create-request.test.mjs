// Run with: node --test tests/forward-create-request.test.mjs (from vite-frontend).
// Compile the production helper instead of reimplementing its behavior here.
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const temporary = mkdtempSync(resolve(tmpdir(), 'lunaris-forward-create-request-'));
after(() => {
  const resolved = resolve(temporary);
  if (!resolved.startsWith(resolve(tmpdir()) + sep)) throw new Error('Unsafe temporary cleanup path');
  rmSync(resolved, { recursive: true, force: true });
});
execFileSync(process.execPath, [resolve(frontend, 'node_modules/typescript/bin/tsc'),
  resolve(frontend, 'src/utils/forward-create-request.ts'), '--outDir', temporary, '--target', 'ES2020',
  '--module', 'ES2020', '--moduleResolution', 'bundler', '--strict', '--skipLibCheck',
], { cwd: frontend, stdio: 'pipe' });
writeFileSync(resolve(temporary, 'package.json'), '{"type":"module"}');
const { createForwardRequestCache } = await import(pathToFileURL(resolve(temporary, 'forward-create-request.js')).href);
test('an uncertain request keeps its identity through retries and edit/undo', () => {
  let sequence = 0;
  const cache = createForwardRequestCache(() => `request-${++sequence}`);
  const original = {name: 'one', tunnelId: 3, inPort: null};
  const first = cache.get(original);
  assert.equal(cache.get({...original}), first);
  assert.notEqual(cache.get({...original, name: 'two'}), first);
  assert.equal(cache.get(original), first);
  assert.notEqual(cache.get(original, 'another-account'), first);
});
test('a confirmed request permits a deliberate subsequent create', () => {
  let sequence = 0;
  const cache = createForwardRequestCache(() => `request-${++sequence}`);
  const payload = {name: 'one'};
  const first = cache.get(payload);
  cache.acknowledge(payload);
  assert.notEqual(cache.get(payload), first);
});
