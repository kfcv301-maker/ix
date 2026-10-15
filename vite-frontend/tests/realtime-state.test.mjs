import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const temporary = mkdtempSync(resolve(tmpdir(), 'lunaris-realtime-state-'));
after(() => {
  if (!resolve(temporary).startsWith(resolve(tmpdir()) + sep)) throw new Error('Unsafe temporary cleanup path');
  rmSync(temporary, { recursive: true, force: true });
});
execFileSync(process.execPath, [resolve(frontend, 'node_modules/typescript/bin/tsc'),
  resolve(frontend, 'src/utils/realtime-state.ts'), '--outDir', temporary, '--target', 'ES2020',
  '--module', 'ES2020', '--moduleResolution', 'bundler', '--strict', '--skipLibCheck',
], { cwd: frontend, stdio: 'pipe' });
writeFileSync(resolve(temporary, 'package.json'), '{"type":"module"}');
const { acceptRealtimeMetric, createRealtimeConnectionGate, mergeRealtimeNodes, shouldResetRealtimeMetrics } =
  await import(pathToFileURL(resolve(temporary, 'realtime-state.js')).href);

test('only one ticket request can start, and an invalidated response cannot win a foreground reconnect', () => {
  const gate = createRealtimeConnectionGate();
  const first = gate.begin();
  assert.equal(gate.begin(), undefined);
  gate.invalidate();
  const second = gate.begin();
  assert.equal(gate.settle(first), false);
  assert.equal(gate.current(first), false);
  assert.equal(gate.begin(), undefined); // stale completion cannot release the current request
  assert.equal(gate.settle(second), true);
  assert.equal(gate.current(second), true);
});

test('page disposal invalidates open-socket callbacks and late tickets', () => {
  const gate = createRealtimeConnectionGate();
  const attempt = gate.begin();
  gate.settle(attempt);
  gate.invalidate();
  assert.equal(gate.current(attempt), false);
  assert.equal(gate.settle(attempt), false);
});

test('a replacement Agent resets previous detailed metrics while duplicate online events retain them', () => {
  assert.equal(shouldResetRealtimeMetrics('old-agent', 'new-agent'), true);
  assert.equal(shouldResetRealtimeMetrics(undefined, 'new-agent'), true);
  assert.equal(shouldResetRealtimeMetrics('same-agent', 'same-agent'), false);
  assert.equal(shouldResetRealtimeMetrics('same-agent', undefined), false); // legacy server compatibility
});

test('an older snapshot arriving after a live report cannot regress counters or create a speed spike', () => {
  const live = acceptRealtimeMetric({ sequence: 101, reportedAt: 20_000, serverNow: 21_000 }, undefined, 50_000);
  assert.equal(live.reportedAt, 49_000);
  assert.equal(acceptRealtimeMetric({ sequence: 100, reportedAt: 18_000 }, live.sequence), undefined);
  assert.equal(acceptRealtimeMetric({ sequence: 101, reportedAt: 20_000 }, live.sequence), undefined);
  const next = acceptRealtimeMetric({ sequence: 102, reportedAt: 22_000 }, live.sequence, 50_001);
  assert.equal((next.sampledAt - live.sampledAt) / 1_000, 2); // sample timestamps, not near-simultaneous replay arrival
});

test('legacy timestamps are compatible, and a fresh connection accepts a restarted server sequence', () => {
  assert.deepEqual(acceptRealtimeMetric({}, undefined, 1234), { sequence: undefined, reportedAt: 1234, sampledAt: undefined });
  assert.deepEqual(acceptRealtimeMetric({ sequence: -1, reportedAt: 'invalid' }, undefined, 1234),
    { sequence: undefined, reportedAt: 1234, sampledAt: undefined });
  assert.equal(acceptRealtimeMetric({ sequence: 1, reportedAt: 2000 }, undefined).sequence, 1);
});

test('server clock skew does not hide fresh samples, while snapshot age remains visible', () => {
  const behind = acceptRealtimeMetric({ reportedAt: 1000, serverNow: 1500 }, undefined, 900_000);
  const ahead = acceptRealtimeMetric({ reportedAt: 9_000_000, serverNow: 9_000_500 }, undefined, 900_000);
  assert.equal(behind.reportedAt, 899_500);
  assert.equal(ahead.reportedAt, 899_500);
  assert.equal(900_000 - behind.reportedAt, 500);
  assert.equal(900_000 - ahead.reportedAt, 500);
});

test('list reload preserves received samples only for the same online node and removes revoked nodes', () => {
  const info = { cpuUsage: 23, tcpConnections: 0, memoryAvailable: undefined };
  const previous = [{ id: 1, connectionStatus: 'online', systemInfo: info },
    { id: 2, connectionStatus: 'online', systemInfo: { cpuUsage: 40 } },
    { id: 3, connectionStatus: 'online', systemInfo: { cpuUsage: 90 } }];
  const merged = mergeRealtimeNodes([{ id: 1, status: 1, name: 'renamed' }, { id: 2, status: 0 }, { id: 4, status: 1 }], previous);
  assert.equal(merged[0].systemInfo, info);
  assert.equal(merged[0].name, 'renamed');
  assert.equal(merged[1].systemInfo, null);
  assert.equal(merged[2].systemInfo, null);
  assert.equal(merged.some(node => node.id === 3), false);
  assert.equal(merged[0].systemInfo.tcpConnections, 0);
  assert.equal(merged[0].systemInfo.memoryAvailable, undefined);
});

test('a slow list response cannot overwrite newer live online or offline events, including across reconnects', () => {
  const startedAt = new Map([[1, 40], [2, 41], [3, 42]]);
  const current = new Map([[1, 43], [2, 44], [3, 42]]);
  const info = { cpuUsage: 10 };
  const live = [{ id: 1, connectionStatus: 'online', connectionId: 'new-agent', systemInfo: info },
    { id: 2, connectionStatus: 'offline', connectionId: 'old-agent', systemInfo: null },
    { id: 3, connectionStatus: 'online', connectionId: 'third-agent', systemInfo: info }];
  const response = [{ id: 1, status: 0 }, { id: 2, status: 1 }, { id: 3, status: 0 }];
  const merged = mergeRealtimeNodes(response, live, startedAt, current);
  assert.equal(merged[0].connectionStatus, 'online');
  assert.equal(merged[0].status, 1);
  assert.equal(merged[0].systemInfo, info);
  assert.equal(merged[0].connectionId, 'new-agent');
  assert.equal(merged[1].connectionStatus, 'offline');
  assert.equal(merged[1].status, 0);
  assert.equal(merged[1].systemInfo, null);
  assert.equal(merged[2].connectionStatus, 'offline'); // no newer event: accept DB state
});
