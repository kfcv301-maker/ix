import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const temporary = mkdtempSync(resolve(tmpdir(), 'lunaris-tunnel-diagnosis-'));
after(() => {
  if (!resolve(temporary).startsWith(resolve(tmpdir()) + sep)) throw new Error('Unsafe temporary cleanup path');
  rmSync(temporary, { recursive: true, force: true });
});
execFileSync(process.execPath, [resolve(frontend, 'node_modules/typescript/bin/tsc'),
  resolve(frontend, 'src/utils/tunnel-diagnosis.ts'), '--outDir', temporary, '--target', 'ES2020',
  '--module', 'ES2020', '--moduleResolution', 'bundler', '--strict', '--skipLibCheck',
], { cwd: frontend, stdio: 'pipe' });
writeFileSync(resolve(temporary, 'package.json'), '{"type":"module"}');
const { createTunnelDiagnosisController: create, diagnosisProgressText: progress } =
  await import(pathToFileURL(resolve(temporary, 'tunnel-diagnosis.js')).href);

const deferred = () => { let resolve; let reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
const flush = () => new Promise(resolve => setImmediate(resolve));
const summary = (extra = {}) => ({ taskId: 'task-a', tunnelId: 11, tunnelName: 'Tunnel', status: 'running',
  totalForwards: 45, completedForwards: 0, successfulForwards: 0, failedForwards: 0, skippedForwards: 0,
  currentBatch: 1, totalBatches: 3, completedBatches: 0, batchSize: 20, forwards: [], ...extra });
const ok = data => ({ code: 0, data });
function harness(api) {
  const pending = new Map(); let next = 0; let latest; const history = [];
  const controller = create(api, value => { latest = value; history.push(value); }, {
    set: callback => { const id = ++next; pending.set(id, callback); return id; },
    clear: id => pending.delete(id),
  });
  return { controller, history, get state() { return latest; }, get pending() { return pending.size; },
    async tick() { const [id, callback] = pending.entries().next().value; pending.delete(id); callback(); await flush(); } };
}
const input = { tunnelId: 11, tunnelName: 'Tunnel', totalForwards: 45 };

test('45 forwards are one server-owned request with serial progress across three automatic batches', async () => {
  let starts = 0; let gets = 0; const responses = [
    summary({ completedForwards: 20, successfulForwards: 20, currentBatch: 2, completedBatches: 1 }),
    summary({ completedForwards: 40, successfulForwards: 40, currentBatch: 3, completedBatches: 2 }),
    summary({ status: 'completed', completedForwards: 45, successfulForwards: 45, currentBatch: 3, completedBatches: 3 }),
  ];
  const h = harness({ start: async () => { starts++; return ok(summary({ status: 'queued', currentBatch: 0 })); },
    get: async () => { gets++; return ok(responses.shift()); }, cancel: async () => ok(summary({ status: 'cancelled' })) });
  await h.controller.start(input);
  assert.equal(h.state.loading, true); assert.equal(h.pending, 1);
  await h.tick(); assert.equal(h.state.summary.currentBatch, 2);
  await h.tick(); assert.equal(h.state.summary.currentBatch, 3);
  await h.tick(); assert.equal(h.state.loading, false); assert.equal(h.pending, 0);
  assert.equal(starts, 1); assert.equal(gets, 3);
  assert.match(progress(h.state.summary, false), /45\/45.*3\/3/);
});

test('closing before task creation returns still cancels the late task', async () => {
  const creation = deferred(); const cancelled = [];
  const h = harness({ start: () => creation.promise, get: async () => { throw new Error('must not poll'); },
    cancel: async id => { cancelled.push(id); return ok(summary({ status: 'cancelled' })); } });
  const start = h.controller.start(input); h.controller.close();
  creation.resolve(ok(summary())); await start; await flush();
  assert.deepEqual(cancelled, ['task-a']); assert.equal(h.state.summary, null); assert.equal(h.pending, 0);
});

test('restarting discards an old in-flight poll and cancels its known task', async () => {
  const poll = deferred(); let starts = 0; const cancelled = [];
  const h = harness({ start: async () => ok(summary({ taskId: ++starts === 1 ? 'task-a' : 'task-b' })),
    get: () => poll.promise, cancel: async id => { cancelled.push(id); return ok(summary({ status: 'cancelled' })); } });
  await h.controller.start(input); await h.tick();
  assert.equal(h.pending, 0, 'no overlapping polls while one request is in flight');
  await h.controller.start(input); poll.resolve(ok(summary({ status: 'completed', completedForwards: 45 }))); await flush();
  assert.equal(h.state.summary.taskId, 'task-b'); assert.equal(h.state.loading, true);
  assert.deepEqual(cancelled, ['task-a']); assert.equal(h.pending, 1);
  h.controller.close();
});

test('a temporary polling failure retains prior progress and retries without duplicate starts', async () => {
  let gets = 0;
  const h = harness({ start: async () => ok(summary({ completedForwards: 20 })),
    get: async () => { if (++gets === 1) throw new Error('offline'); return ok(summary({ status: 'completed', completedForwards: 45 })); },
    cancel: async () => ok(summary({ status: 'cancelled' })) });
  await h.controller.start(input); await h.tick();
  assert.equal(h.state.summary.completedForwards, 20); assert.equal(h.state.loading, true);
  assert.match(h.state.error, /自动重试/); assert.equal(h.pending, 1);
  await h.tick(); assert.equal(h.state.loading, false); assert.equal(h.state.error, null);
});

test('a resolved network code -1 keeps the task cancellable and resumes polling', async () => {
  let gets = 0;
  const h = harness({ start: async () => ok(summary({ completedForwards: 20 })),
    get: async () => ++gets === 1 ? { code: -1, msg: 'Network Error' }
      : ok(summary({ status: 'completed', completedForwards: 45 })),
    cancel: async () => ok(summary({ status: 'cancelled' })) });
  await h.controller.start(input); await h.tick();
  assert.equal(h.state.loading, true); assert.equal(h.state.summary.taskId, 'task-a');
  assert.equal(h.state.summary.completedForwards, 20); assert.equal(h.pending, 1);
  await h.tick(); assert.equal(h.state.loading, false); assert.equal(h.state.summary.completedForwards, 45);
});

test('explicit 404 and the exact legacy expired-task message stop polling', async () => {
  for (const response of [{ code: 404, msg: '诊断任务不存在或已过期' }, { code: -1, msg: '诊断任务不存在或已过期' }]) {
    const h = harness({ start: async () => ok(summary()), get: async () => response,
      cancel: async () => ok(summary({ status: 'cancelled' })) });
    await h.controller.start(input); await h.tick();
    assert.equal(h.state.loading, false); assert.equal(h.pending, 0); assert.equal(h.state.error, response.msg);
  }
});

test('explicit cancellation keeps the terminal partial report and stops polling', async () => {
  const h = harness({ start: async () => ok(summary({ completedForwards: 20, successfulForwards: 19, failedForwards: 1 })),
    get: async () => ok(summary()), cancel: async () => ok(summary({ status: 'cancelled', completedForwards: 20,
      successfulForwards: 19, failedForwards: 1, skippedForwards: 25, message: '用户已取消剩余检测' })) });
  await h.controller.start(input); await h.controller.cancel();
  assert.equal(h.pending, 0); assert.equal(h.state.loading, false);
  assert.equal(h.state.summary.failedForwards, 1); assert.equal(h.state.summary.skippedForwards, 25);
  assert.match(progress(h.state.summary, false), /已取消.*20\/45.*25 条未检测/);
});

test('a delayed old running poll cannot overwrite a cancellation response', async () => {
  const poll = deferred();
  const h = harness({ start: async () => ok(summary()), get: () => poll.promise,
    cancel: async () => ok(summary({ status: 'cancelled', skippedForwards: 45 })) });
  await h.controller.start(input); await h.tick(); await h.controller.cancel();
  poll.resolve(ok(summary({ currentBatch: 2 }))); await flush();
  assert.equal(h.state.summary.status, 'cancelled'); assert.equal(h.pending, 0);
});

test('a cancellation transport failure remains visible and allows retry', async () => {
  let cancels = 0;
  const h = harness({ start: async () => ok(summary()), get: async () => ok(summary()),
    cancel: async () => { if (++cancels === 1) throw new Error('offline'); return ok(summary({ status: 'cancelled' })); } });
  await h.controller.start(input); await h.controller.cancel();
  assert.equal(h.state.loading, true); assert.equal(h.state.cancelling, false); assert.match(h.state.error, /未能确认取消/);
  await h.controller.cancel(); assert.equal(h.state.loading, false); assert.equal(h.pending, 0);
});

test('expired or unauthorized task stops polling and shows the server explanation', async () => {
  const h = harness({ start: async () => ok(summary()), get: async () => ({ code: 403, msg: '无权查看任务' }),
    cancel: async () => ok(summary({ status: 'cancelled' })) });
  await h.controller.start(input); await h.tick();
  assert.equal(h.state.loading, false); assert.equal(h.pending, 0); assert.equal(h.state.error, '无权查看任务');
});

test('empty completed tasks and start errors never manufacture a failed forward', async () => {
  const empty = harness({ start: async () => ok(summary({ status: 'completed', totalForwards: 0, totalBatches: 0 })),
    get: async () => { throw new Error('must not poll'); }, cancel: async () => ok(summary({ status: 'cancelled' })) });
  await empty.controller.start(input); assert.equal(empty.state.loading, false); assert.equal(empty.pending, 0);
  const denied = harness({ start: async () => ({ code: 429, msg: '请稍后再试' }),
    get: async () => { throw new Error('must not poll'); }, cancel: async () => ok(summary({ status: 'cancelled' })) });
  await denied.controller.start(input); assert.equal(denied.state.loading, false); assert.equal(denied.state.error, '请稍后再试');
  assert.equal(denied.state.summary.failedForwards, 0); assert.deepEqual(denied.state.summary.forwards, []);
});
