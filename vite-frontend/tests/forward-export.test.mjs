import { after, test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const temporary = mkdtempSync(resolve(tmpdir(), 'lunaris-forward-export-'));
after(() => {
  if (!resolve(temporary).startsWith(resolve(tmpdir()) + sep)) throw new Error('Unsafe temporary cleanup path');
  rmSync(temporary, { recursive: true, force: true });
});
execFileSync(process.execPath, [resolve(frontend, 'node_modules/typescript/bin/tsc'),
  resolve(frontend, 'src/utils/forward-export.ts'), '--outDir', temporary, '--target', 'ES2020',
  '--module', 'ES2020', '--moduleResolution', 'bundler', '--strict', '--skipLibCheck',
], { cwd: frontend, stdio: 'pipe' });
writeFileSync(resolve(temporary, 'package.json'), '{"type":"module"}');
const { selectForwardExportRecords: select, createForwardExport: bundle, parseForwardImport: parse,
  parseForwardInterface: parseInterface } = await import(pathToFileURL(resolve(temporary, 'forward-export.js')).href);
const { parseProxyProtocol } = await import(pathToFileURL(resolve(temporary, 'forward-proxy-protocol.js')).href);
const source = (id, extra = {}) => ({ id, name: `Rule ${id}`, tunnelId: 9, remoteAddr: '[2001:db8::1]:443,a.example:443',
  inPort: 4000 + id, interfaceName: 'eth1', strategy: 'round', proxyProtocol: 2, ...extra });

test('filtered export includes every matching page and excludes hidden records in other tunnels', () => {
  const all = Array.from({ length: 55 }, (_, i) => source(i + 1, { tunnelId: i % 2 ? 9 : 10 }));
  const filtered = all.filter(item => item.id % 2 === 0);
  const result = select('filtered', null, all, filtered);
  assert.equal(result.length, 27);
  assert.deepEqual(result.map(item => item.id), filtered.map(item => item.id));
  assert.equal(result.includes(all[0]), false);
  assert.equal(bundle(result, null).forwards.length, 27);
});

test('whole tunnel export retains its explicit scope even while the list is filtered', () => {
  const all = [source(1), source(2), source(3, { tunnelId: 10 })];
  assert.deepEqual(select('tunnel', 9, all, [all[0]]).map(item => item.id), [1, 2]);
  assert.deepEqual(select('tunnel', 10, all, [all[0]]).map(item => item.id), [3]);
  assert.deepEqual(select('tunnel', null, all, [all[0]]), []);
  assert.deepEqual(select('filtered', 9, all, []), []);
  assert.throws(() => bundle([], null), /没有转发数据/);
});

test('export is a portable whitelist without infrastructure, ownership, credentials or VPS binding', () => {
  const record = source(1, { inIp: 'hidden-node.invalid', entryDomain: 'assigned-user.invalid', serverIp: '192.0.2.1',
    nodeSecret: 'private-marker', userId: 12, userName: 'another-user', vpsHostId: 77, syncError: 'internal detail' });
  const before = structuredClone(record);
  const result = bundle([record], 9, new Date('2026-10-23T00:00:00Z'));
  assert.deepEqual(result, { format: 'flux-panel-forward-export', version: 2, exportedAt: '2026-10-23T00:00:00.000Z', tunnelId: 9,
    forwards: [{name:'Rule 1', remoteAddr:record.remoteAddr, inPort:4001, interfaceName:'eth1', strategy:'round', proxyProtocol:2}] });
  assert.deepEqual(record, before);
});

test('JSON round trip preserves an explicit outbound interface, strategy and Proxy Protocol', () => {
  for (const interfaceName of ['eth1', '192.0.2.2', '2001:db8::2', '']) {
    const item = parse(JSON.stringify(bundle([source(1, { interfaceName })], 9)))[0];
    assert.equal(parseInterface(item.interfaceName), interfaceName);
    assert.equal(item.strategy, 'round');
    assert.equal(parseProxyProtocol(item.proxyProtocol), 2);
    assert.equal(item.remoteAddr, source(1).remoteAddr);
  }
});

test('old v2 files and pipe-delimited backups keep auto interface and unchanged TCP defaults', () => {
  const oldJson = parse(JSON.stringify({ format:'flux-panel-forward-export', version:2, forwards:[{
    name:'legacy',remoteAddr:'a.example:443',inPort:4000,strategy:'rand',
  }] }))[0];
  const oldText = parse('a.example:443|legacy|4000|hidden-node.invalid')[0];
  for (const item of [oldJson, oldText]) {
    assert.equal(parseInterface(item.interfaceName), '');
    assert.equal(parseProxyProtocol(item.proxyProtocol), 0);
    assert.equal(item.remoteAddr, 'a.example:443');
    assert.equal(item.name, 'legacy');
    assert.equal(Object.hasOwn(item, 'inIp'), false);
  }
  assert.equal(oldJson.strategy, 'rand');
  assert.equal(oldText.strategy, 'fifo');
});

test('malformed interface values are rejected before any create request', () => {
  for (const value of [true, [], {}, 3, 'eth 1', 'eth0\neth1', 'x'.repeat(129), '\u0000eth0']) {
    assert.throws(() => parseInterface(value), /出口接口/);
  }
  assert.equal(parseInterface('  eth1  '), 'eth1');
  assert.equal(parseInterface(null), '');
  assert.throws(() => bundle([source(1, { proxyProtocol: 3 })], 9), /Proxy Protocol/);
});

test('unsupported or malformed JSON cannot be mistaken for a successful backup import', () => {
  assert.throws(() => parse('{invalid json'));
  assert.throws(() => parse('{"format":"other","version":2,"forwards":[]}'), /v2/);
  assert.throws(() => parse('{"format":"flux-panel-forward-export","version":3,"forwards":[]}'), /v2/);
  assert.throws(() => parse('{"format":"flux-panel-forward-export","version":2,"forwards":{}}'), /v2/);
});
