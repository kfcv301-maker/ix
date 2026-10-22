import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const temporary = mkdtempSync(resolve(tmpdir(), 'lunaris-entry-address-'));
after(() => {
  if (!resolve(temporary).startsWith(resolve(tmpdir()) + sep)) throw new Error('Unsafe temporary cleanup path');
  rmSync(temporary, { recursive: true, force: true });
});
execFileSync(process.execPath, [resolve(frontend, 'node_modules/typescript/bin/tsc'),
  resolve(frontend, 'src/utils/entry-address.ts'), '--outDir', temporary, '--target', 'ES2020',
  '--module', 'ES2020', '--moduleResolution', 'bundler', '--strict', '--skipLibCheck',
], { cwd: frontend, stdio: 'pipe' });
writeFileSync(resolve(temporary, 'package.json'), '{"type":"module"}');
const { assignedEntryAddress: address } = await import(pathToFileURL(resolve(temporary, 'entry-address.js')).href);

const infrastructure = { inIp: 'hidden-node.example', ip: 'default.example' };

test('tenant A and B see their own assigned domains on the same infrastructure', () => {
  const userA = { ...infrastructure, entryAddressMode: 'CUSTOM', entryDomain: ' a.example ' };
  const userB = { ...infrastructure, entryAddressMode: 'CUSTOM', entryDomain: 'b.example' };
  for (const canManage of [true, false]) {
    assert.equal(address(userA, canManage), 'a.example');
    assert.equal(address(userB, canManage), 'b.example');
  }
});

test('missing or deleted domain assignments never expose infrastructure, including to the forward viewer', () => {
  for (const entryAddressMode of ['CUSTOM', 'DEFAULT']) {
    for (const entryDomain of ['', '  ', null, undefined]) {
      for (const canManage of [true, false]) {
        assert.equal(address({ ...infrastructure, entryAddressMode, entryDomain }, canManage), '');
      }
    }
  }
});

test('an expired or revoked assignment cannot be revived by a stale domain or raw address', () => {
  assert.equal(address({ ...infrastructure, entryAddressMode: 'UNASSIGNED', entryDomain: 'old.example' }), '');
  assert.equal(address({ ...infrastructure, entryAddressMode: 'UNASSIGNED', entryDomain: 'old.example' }, true), '');
});

test('legacy or partial shared records fail closed until an explicit assignment arrives', () => {
  assert.equal(address(infrastructure), '');
  assert.equal(address({ ...infrastructure, entryAddressMode: null }), '');
  assert.equal(address({ ...infrastructure, entryAddressMode: 'unknown' }), '');
});

test('explicit raw-entry permission preserves multi-IP and IPv6 ingress addresses', () => {
  assert.equal(address({ entryAddressMode: 'NONE', inIp: ' 192.0.2.1,2001:db8::1 ' }), '192.0.2.1,2001:db8::1');
  assert.equal(address({ entryAddressMode: 'NONE', ip: 'raw-authorized.example' }), 'raw-authorized.example');
  assert.equal(address({ entryAddressMode: 'NONE', inIp: null }), '');
});

test('infrastructure managers can view their own unassigned tunnel without changing shared policy', () => {
  assert.equal(address(infrastructure, true), 'hidden-node.example');
  assert.equal(address({ ip: 'owned.example' }, true), 'owned.example');
  assert.equal(address({}, true), '');
});
