// Run with: node --test tests/forward-bulk.test.mjs (from vite-frontend).
// Compile the production helper instead of reimplementing its behavior here.
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const temporary = mkdtempSync(resolve(tmpdir(), 'lunaris-forward-bulk-'));
after(() => {
  const resolved = resolve(temporary);
  if (!resolved.startsWith(resolve(tmpdir()) + sep)) throw new Error('Unsafe temporary cleanup path');
  rmSync(resolved, { recursive: true, force: true });
});
execFileSync(process.execPath, [resolve(frontend, 'node_modules/typescript/bin/tsc'),
  resolve(frontend, 'src/utils/forward-bulk.ts'), '--outDir', temporary, '--target', 'ES2020',
  '--module', 'ES2020', '--moduleResolution', 'bundler', '--strict', '--skipLibCheck',
], { cwd: frontend, stdio: 'pipe' });
writeFileSync(resolve(temporary, 'package.json'), '{"type":"module"}');
const { planBulkForwardUpdates: plan, executeBulkForwardUpdates: execute, normalizeBulkTargetAddresses: targets } =
  await import(pathToFileURL(resolve(temporary, 'forward-bulk.js')).href);

const access = { isAdministrator: false, currentUserId: 7 };
const rule = (id = 1, extra = {}) => ({ id, userId: 7, name: `Rule ${id}`, tunnelId: 9, inPort: 4000 + id,
  remoteAddr: 'a.example:443,b.example:443', interfaceName: 'eth0', strategy: 'round', vpsHostId: 3,
  status: 1, ...extra });
const outcome = async (entries, api) => {
  const results = [];
  await execute(entries, access, api, result => results.push(result));
  return results.filter(result => result.state !== 'applying');
};

test('selective target changes preserve unchecked fields and never include unselected rules', () => {
  const sources = [rule(), rule(2)];
  const original = structuredClone(sources);
  const entries = plan(sources, [1, 1], { remoteAddr: ' c.example:8443\n[2001:db8::1]:443 ' }, access);
  assert.equal(entries.length, 1);
  assert.deepEqual(entries[0].changes, [{ field: 'remoteAddr', before: sources[0].remoteAddr, after: 'c.example:8443,[2001:db8::1]:443' }]);
  assert.deepEqual(entries[0].payload, { id: 1, userId: 7, name: 'Rule 1', tunnelId: 9, inPort: 4001,
    remoteAddr: 'c.example:8443,[2001:db8::1]:443', interfaceName: 'eth0', strategy: 'round' });
  assert.deepEqual(sources, original);
  // Association fields are omitted, as the existing API preserves them.
  assert.equal(Object.hasOwn(entries[0].payload, 'vpsHostId'), false);
  assert.equal(Object.hasOwn(entries[0].payload, 'clearVpsHost'), false);
  sources[0].name = 'Later change';
  assert.equal(entries[0].before.name, 'Rule 1');
});

test('checked empty interface clears only that field; a single target does not rewrite unchecked strategy', () => {
  const entry = plan([rule()], [1], { interfaceName: '  ' }, access)[0];
  assert.equal(entry.payload.interfaceName, '');
  assert.equal(entry.payload.remoteAddr, rule().remoteAddr);
  assert.equal(entry.payload.strategy, 'round');
  const targetEntry = plan([rule()], [1], { remoteAddr: 'single.example:443' }, access)[0];
  assert.equal(targetEntry.payload.strategy, 'round');
});

test('IPv4, IPv6 and DNS targets validate locally, including port bounds and malformed inputs', () => {
  assert.equal(targets('127.0.0.1:1, [::ffff:192.0.2.1]:65535\nfoo-bar.example:443'), '127.0.0.1:1,[::ffff:192.0.2.1]:65535,foo-bar.example:443');
  for (const bad of ['', '256.1.1.1:443', '[bad::address]:443', '[::1]:0', 'example.com:65536', 'https://example.com:443', 'a@b:443', 'foo:443/path', '::1:443', 'bad-.example:443']) assert.throws(() => targets(bad), undefined, bad);
  assert.throws(() => plan([rule()], [1], {}, access), /勾选/);
  assert.throws(() => plan([rule()], [1], { strategy: 'unknown' }, access), /策略/);
  assert.throws(() => plan([rule()], [1], { interfaceName: 'bad interface' }, access), /空白/);
});

test('paused, abnormal, syncing, missing and unauthorized rules are previewed as skipped', () => {
  const sources = [rule(1, { status: 0 }), rule(2, { status: -1 }), rule(3, { status: 2 }),
    rule(4, { status: 3 }), rule(5, { syncState: 'partial' }), rule(6, { userId: 8 }), rule(7, { userId: undefined })];
  const entries = plan(sources, [1, 2, 3, 4, 5, 6, 7, 99], { interfaceName: '' }, access);
  assert.ok(entries.every(entry => entry.state === 'skipped' && entry.payload === undefined));
  assert.match(entries[0].message, /保留暂停状态/);
  assert.equal(plan([rule()], [1], { strategy: 'round' }, access)[0].state, 'skipped');
  assert.equal(plan([rule()], [1], { strategy: 'hash' }, access)[0].state, 'ready');
  assert.match(plan([rule(1, { remoteAddr: 'single:443' })], [1], { strategy: 'hash' }, access)[0].message, /单个目标/);
  assert.equal(plan([rule(1, { userId: 8 })], [1], { interfaceName: '' }, { isAdministrator: true, currentUserId: 1 })[0].state, 'ready');
});

test('configuration conflicts block writes, including changes to unchecked fields or VPS association', async () => {
  const entries = plan([rule()], [1], { interfaceName: '' }, access);
  for (const change of [{ name: 'Edited elsewhere' }, { vpsHostId: 4 }, { inPort: 5000 }, { status: 0 }, { syncState: 'syncing' }, { userId: 8 }]) {
    let writes = 0;
    const results = await outcome(entries, { list: async () => [rule(1, change)], update: async () => { writes++; return { code: 0 }; } });
    assert.equal(results[0].state, 'conflict');
    assert.equal(writes, 0);
  }
  const gone = await outcome(entries, { list: async () => [], update: async () => { assert.fail('Missing rule must not be updated'); } });
  assert.equal(gone[0].state, 'conflict');
});

test('requests are sequential, report individual failures, and do not touch skipped or unselected rules', async () => {
  const sources = [rule(), rule(2), rule(3, { status: 0 }), rule(4)];
  const entries = plan(sources, [1, 2, 3], { interfaceName: '' }, access);
  const writes = []; const events = [];
  let active = false;
  const results = await outcome(entries, {
    list: async () => { assert.equal(active, false); events.push('list'); return sources; },
    update: async payload => {
      assert.equal(active, false); active = true;
      await new Promise(resolve => setImmediate(resolve));
      writes.push(payload.id); events.push(`update ${payload.id}`); active = false;
      return payload.id === 1 ? { code: -1, msg: 'Node offline' } : { code: 0 };
    },
  });
  assert.deepEqual(writes, [1, 2]);
  assert.deepEqual(events, ['list', 'update 1', 'list', 'update 2']);
  assert.deepEqual(results.map(result => result.state), ['failed', 'success', 'skipped']);
  assert.equal(results[0].message, 'Node offline');
});

test('traffic counters do not create false configuration conflicts', async () => {
  const source = rule(); let writes = 0;
  const results = await outcome(plan([source], [1], { interfaceName: '' }, access), {
    list: async () => [{ ...source, inFlow: 999, outFlow: 111, updatedTime: Date.now() }],
    update: async () => { writes++; return { code: 0 }; },
  });
  assert.equal(writes, 1);
  assert.equal(results[0].state, 'success');
});

test('a lost response is reported as failure, but retry checks for an applied config and never reapplies it', async () => {
  let current = rule(); let writes = 0;
  const entries = plan([current], [1], { interfaceName: '' }, access);
  const api = {
    list: async () => [current],
    update: async payload => { writes++; current = { ...current, ...payload }; throw new Error('Response lost'); },
  };
  const initial = await outcome(entries, api);
  assert.equal(initial[0].state, 'failed');
  const retry = await outcome([entries[0]], api);
  assert.equal(retry[0].state, 'skipped');
  assert.match(retry[0].message, /已生效/);
  assert.equal(writes, 1);
});

test('one failed rule can be retried without rebuilding successful rules', async () => {
  const sources = [rule(), rule(2)];
  const entries = plan(sources, [1, 2], { interfaceName: '' }, access);
  const writes = []; let fail = true;
  const api = { list: async () => sources, update: async payload => {
    writes.push(payload.id);
    if (payload.id === 1 && fail) return { code: -1, msg: 'Try again' };
    const index = sources.findIndex(source => source.id === payload.id);
    sources[index] = { ...sources[index], ...payload };
    return { code: 0 };
  } };
  const initial = await outcome(entries, api);
  fail = false;
  await outcome(entries.filter(entry => initial.find(result => result.id === entry.id)?.state === 'failed'), api);
  assert.deepEqual(writes, [1, 2, 1]);
  assert.equal(sources[0].interfaceName, '');
  assert.equal(sources[1].interfaceName, '');
});
