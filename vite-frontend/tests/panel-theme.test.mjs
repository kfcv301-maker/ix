import { after, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const temporary = mkdtempSync(resolve(tmpdir(), 'lunaris-theme-'));
after(() => {
  if (!resolve(temporary).startsWith(resolve(tmpdir()) + sep)) throw new Error('Unsafe temporary cleanup path');
  rmSync(temporary, { recursive: true, force: true });
});
execFileSync(process.execPath, [resolve(frontend, 'node_modules/typescript/bin/tsc'),
  resolve(frontend, 'src/utils/panel-theme.ts'), resolve(frontend, 'src/utils/logout.ts'),
  '--outDir', temporary, '--target', 'ES2020', '--module', 'commonjs', '--strict', '--skipLibCheck',
], { cwd: frontend, stdio: 'pipe' });
const require = createRequire(import.meta.url);
const preferences = require(resolve(temporary, 'panel-theme.js'));
const { safeLogout } = require(resolve(temporary, 'logout.js'));
const { PANEL_THEME_STORAGE_KEY: themeKey, PANEL_APPEARANCE_STORAGE_KEY: appearanceKey } = preferences;

beforeEach(() => {
  const values = new Map();
  globalThis.localStorage = {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, String(value)),
    clear: () => values.clear(),
  };
  globalThis.window = new EventTarget();
  const attributes = new Map();
  globalThis.document = { documentElement: {
    setAttribute: (key, value) => attributes.set(key, value),
    removeAttribute: key => attributes.delete(key),
    getAttribute: key => attributes.get(key) ?? null,
  } };
});

test('explicit appearance is independent of the OS; system mode follows it', () => {
  for (const systemDark of [true, false]) {
    assert.equal(preferences.resolvePanelAppearance('light', systemDark), 'light');
    assert.equal(preferences.resolvePanelAppearance('dark', systemDark), 'dark');
    assert.equal(preferences.resolvePanelAppearance('system', systemDark), systemDark ? 'dark' : 'light');
  }
});

test('document and picker subscribers receive changes without losing the other preference', () => {
  const first = [], second = [];
  const unsubscribe = preferences.subscribePanelPreferences(value => first.push(value));
  const unsubscribeSecond = preferences.subscribePanelPreferences(value => second.push(value));
  preferences.savePanelTheme('sakura-glass');
  preferences.savePanelAppearance('dark');
  assert.deepEqual(first, second);
  assert.deepEqual(first.at(-1), { theme: 'sakura-glass', appearance: 'dark' });
  assert.equal(document.documentElement.getAttribute('data-panel-theme'), 'sakura-glass');
  unsubscribe(); unsubscribeSecond();
  const count = first.length;
  preferences.savePanelAppearance('light');
  assert.equal(first.length, count, 'unmounted listeners must be removed');
});

test('storage changes synchronize tabs, reject unrelated stores and reset invalid saved preferences', () => {
  const received = [];
  const unsubscribe = preferences.subscribePanelPreferences(value => received.push(value));
  const dispatch = (key, storageArea = localStorage) => {
    const event = new Event('storage');
    Object.assign(event, { key, storageArea });
    window.dispatchEvent(event);
  };
  localStorage.setItem(themeKey, 'dora-sky');
  localStorage.setItem(appearanceKey, 'system');
  dispatch(themeKey, {});
  dispatch('unrelated');
  assert.equal(received.length, 1);
  dispatch(themeKey);
  assert.deepEqual(received.at(-1), { theme: 'dora-sky', appearance: 'system' });
  localStorage.setItem(themeKey, 'invalid');
  localStorage.setItem(appearanceKey, 'invalid');
  dispatch(null);
  assert.deepEqual(received.at(-1), { theme: 'xboard-console', appearance: 'light' });
  unsubscribe();
});

test('theme controls still update mounted subscribers when browser storage is blocked', () => {
  globalThis.localStorage = {
    getItem() { throw new Error('Storage blocked'); },
    setItem() { throw new Error('Storage blocked'); },
  };
  const received = [];
  const unsubscribe = preferences.subscribePanelPreferences(value => received.push(value));
  preferences.savePanelAppearance('dark');
  preferences.savePanelTheme('nvenix-minimal');
  assert.deepEqual(received.at(-1), { theme: 'nvenix-minimal', appearance: 'dark' });
  assert.equal(document.documentElement.getAttribute('data-panel-theme'), 'nvenix-minimal');
  unsubscribe();
});

test('logout clears session and data while preserving skin, appearance and sidebar preference', () => {
  localStorage.setItem(themeKey, 'dora-sky');
  localStorage.setItem(appearanceKey, 'dark');
  localStorage.setItem('console-sidebar-collapsed', 'true');
  for (const key of ['token', 'name', 'role_id', 'admin', 'cached-api-data']) localStorage.setItem(key, 'session-value');
  safeLogout();
  assert.equal(localStorage.getItem(themeKey), 'dora-sky');
  assert.equal(localStorage.getItem(appearanceKey), 'dark');
  assert.equal(localStorage.getItem('console-sidebar-collapsed'), 'true');
  for (const key of ['token', 'name', 'role_id', 'admin', 'cached-api-data']) assert.equal(localStorage.getItem(key), null);
});
