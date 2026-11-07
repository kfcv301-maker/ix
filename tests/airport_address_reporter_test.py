"""Linux isolated tests; HTTP and systemd are fixtures, never live services."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
from urllib.error import HTTPError

SOURCE = Path(__file__).resolve().parents[1] / 'node-runtime' / 'airport_address_reporter.py'


def load():
    spec = importlib.util.spec_from_file_location('isolated_airport_reporter', SOURCE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ReporterTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='airport-reporter-test-')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / 'relay'
        self.root.mkdir(mode=0o700)
        self.config = self.root / 'config.json'
        self.identity = {'addr': 'https://panel.invalid', 'secret': 'isolated-node-secret-do-not-log'}
        self.config.write_text(json.dumps(self.identity))
        self.config.chmod(0o600)
        self.boot = self.base / 'boot_id'
        self.boot.write_text('11111111-2222-4333-8444-555555555555\n')
        self.module = load()
        self.policy = {'enabled': True, 'sourceMode': 'aws-imds'}
        self.calls = []
        self.posts = []
        self.bad_echo = False
        self.old_panel = False
        self.patches = [mock.patch.object(self.module, 'BOOT_ID', self.boot),
                        mock.patch.object(self.module, 'RUNTIME_DIR', self.base / 'runtime'),
                        mock.patch.object(self.module, 'request', self.http)]
        for patch in self.patches:
            patch.start()
            self.addCleanup(patch.stop)

    def http(self, url, method='GET', headers=None, body=None, timeout=3):
        self.calls.append((url, method, dict(headers or {})))
        if url == 'https://panel.invalid' + self.module.API_PATH:
            self.assertEqual((headers or {}).get('Authorization'), 'Bearer ' + self.identity['secret'])
            if method == 'GET':
                if self.old_panel:
                    raise HTTPError(url, 404, 'fixture', {}, None)
                return json.dumps({'code': 0, 'data': self.policy}).encode()
            self.assertEqual(method, 'POST')
            observation = json.loads(body)
            self.posts.append(observation)
            return json.dumps({'code': 0, 'data': {'accepted': True,
                'ip': '8.8.4.4' if self.bad_echo else observation['ip']}}).encode()
        self.assertTrue(url.startswith(self.module.IMDS))
        self.assertNotIn('Authorization', headers or {})
        if url.endswith('/api/token'):
            self.assertEqual(method, 'PUT')
            self.assertEqual((headers or {}).get('X-aws-ec2-metadata-token-ttl-seconds'), '60')
            return b'isolated-metadata-token'
        self.assertEqual((headers or {}).get('X-aws-ec2-metadata-token'), 'isolated-metadata-token')
        if url.endswith('/instance-id'):
            return b'i-0123456789abcdef0'
        if url.endswith('/public-ipv4'):
            return b'8.8.8.8'
        self.fail('Unexpected fixture request')

    def test_first_observation_has_true_imdsv2_ipv4_and_boot_identity(self):
        result = self.module.locked_once(self.root)
        self.assertEqual(result, {'status': 'reported', 'reported': True})
        self.assertEqual(self.posts[0]['ip'], '8.8.8.8')
        self.assertEqual(self.posts[0]['instanceId'], 'i-0123456789abcdef0')
        self.assertEqual(self.posts[0]['bootId'], self.boot.read_text().strip())
        self.assertIs(type(self.posts[0]['observedAt']), int)
        self.assertLess(abs(self.posts[0]['observedAt'] - self.module.time.time()), 3)
        self.assertEqual([m for _, m, _ in self.calls], ['GET', 'PUT', 'GET', 'GET', 'POST'])

    def test_disabled_and_peer_modes_never_access_imds_or_post(self):
        for policy in [{'enabled': False, 'sourceMode': 'aws-imds'},
                       {'enabled': True, 'sourceMode': 'peer'},
                       {'enabled': False, 'sourceMode': 'off'}]:
            self.policy = policy
            self.calls.clear()
            self.assertEqual(self.module.once(self.root)['status'], 'disabled')
            self.assertEqual(len(self.calls), 1)
        self.assertEqual(self.posts, [])

    def test_old_panel_404_skips_without_metadata(self):
        self.old_panel = True
        self.assertEqual(self.module.once(self.root), {'status': 'unsupported', 'reported': False})
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(self.posts, [])

    def test_ambiguous_enable_flag_is_rejected_before_metadata(self):
        self.policy['enabled'] = 'true'
        with self.assertRaises(ValueError):
            self.module.once(self.root)
        self.assertEqual(len(self.calls), 1)

    def test_nonmatching_ack_never_claims_reported(self):
        self.bad_echo = True
        with self.assertRaises(ValueError):
            self.module.once(self.root)

    def test_failed_policy_does_not_collect_or_print_secrets(self):
        error = HTTPError('https://private.invalid/?token=' + self.identity['secret'], 503,
                          self.identity['secret'], {}, None)
        with mock.patch.object(self.module, 'request', side_effect=error), \
             mock.patch.object(self.module, 'require_root'), contextlib.redirect_stderr(io.StringIO()) as err:
            code = self.module.main(['--once', '--root', str(self.root)])
        self.assertEqual(code, 1)
        self.assertNotIn(self.identity['secret'], err.getvalue())
        self.assertNotIn('private.invalid', err.getvalue())
        self.assertEqual(json.loads(err.getvalue())['httpStatus'], 503)
        self.assertEqual(self.posts, [])

    def test_restart_does_not_generate_a_new_boot_id(self):
        self.module.once(self.root)
        again = load()
        with mock.patch.object(again, 'BOOT_ID', self.boot), mock.patch.object(again, 'request', self.http):
            again.once(self.root)
        self.assertEqual(self.posts[0]['bootId'], self.posts[1]['bootId'])
        self.assertEqual(len(self.posts), 2)

    def test_real_new_process_restarts_use_same_kernel_boot_identity(self):
        runner = self.base / 'child_fixture.py'
        trace = self.base / 'child_observations.jsonl'
        runner.write_text('''import importlib.util,json,os,pathlib,sys
source,root,boot,runtime,trace=sys.argv[1:]
spec=importlib.util.spec_from_file_location('child_reporter',source)
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.BOOT_ID=pathlib.Path(boot);m.RUNTIME_DIR=pathlib.Path(runtime)
m.require_root=lambda:None
def fixture(url,method='GET',headers=None,body=None,timeout=3):
 if url.endswith(m.API_PATH):
  if method=='GET':return b'{"code":0,"data":{"enabled":true,"sourceMode":"aws-imds"}}'
  value=json.loads(body)
  fd=os.open(trace,os.O_CREAT|os.O_WRONLY|os.O_APPEND,0o600)
  with os.fdopen(fd,'w')as out:out.write(json.dumps({'pid':os.getpid(),'observation':value})+'\\n')
  return json.dumps({'code':0,'data':{'accepted':True,'ip':value['ip']}}).encode()
 if url.endswith('/api/token'):return b'child-metadata-token'
 if url.endswith('/instance-id'):return b'i-0123456789abcdef0'
 if url.endswith('/public-ipv4'):return b'8.8.8.8'
 raise AssertionError('Unexpected fixture endpoint')
m.request=fixture
raise SystemExit(m.main(['--once','--root',root]))
''')
        command = [sys.executable, '-B', str(runner), str(SOURCE), str(self.root),
                   str(self.boot), str(self.base / 'child-runtime'), str(trace)]
        for _ in range(2):
            proc = subprocess.run(command, capture_output=True, text=True, timeout=10)
            self.assertEqual(proc.returncode, 0, proc.stderr)
            self.assertEqual(json.loads(proc.stdout), {'status': 'reported', 'reported': True})
            self.assertNotIn(self.identity['secret'], proc.stdout + proc.stderr)
        rows = [json.loads(line) for line in trace.read_text().splitlines()]
        self.assertNotEqual(rows[0]['pid'], rows[1]['pid'])
        self.assertEqual(rows[0]['observation']['bootId'], rows[1]['observation']['bootId'])

    def test_invalid_imds_address_or_identity_never_posts(self):
        original = self.http
        for endpoint, value in [('public-ipv4', b'10.0.0.1'),
                                ('public-ipv4', b'100.64.0.1'),
                                ('public-ipv4', b'2001:db8::1'),
                                ('instance-id', b'i-wrong')]:
            def fixture(url, method='GET', headers=None, body=None, timeout=3):
                if url.endswith('/' + endpoint):
                    return value
                return original(url, method, headers, body, timeout)
            with mock.patch.object(self.module, 'request', side_effect=fixture):
                with self.assertRaises(ValueError):
                    self.module.once(self.root)
        self.assertEqual(self.posts, [])

    def test_private_config_and_symlink_guards_precede_network(self):
        self.config.chmod(0o644)
        with self.assertRaises(PermissionError):
            self.module.once(self.root)
        self.config.chmod(0o600)
        real = self.config.with_suffix('.original')
        self.config.rename(real)
        self.config.symlink_to(real)
        with self.assertRaises(ValueError):
            self.module.once(self.root)
        self.assertEqual(self.calls, [])

    def test_url_and_secret_injection_is_rejected(self):
        for panel in ['http://panel.invalid', 'https://user:pass@panel.invalid',
                      'https://panel.invalid/?q=secret', 'https://panel.invalid/#fragment',
                      'https://panel.invalid/path', 'https://panel.invalid:8443',
                      'https://panel.invalid\nforged']:
            self.config.write_text(json.dumps({**self.identity, 'addr': panel}))
            with self.assertRaises(ValueError):
                self.module.once(self.root)
        self.config.write_text(json.dumps({**self.identity, 'secret': 'bad\r\nHeader: value'}))
        with self.assertRaises(ValueError):
            self.module.once(self.root)
        self.assertEqual(self.calls, [])

    def test_transport_ignores_proxy_environment_and_caps_body(self):
        fresh = load()
        opener = mock.MagicMock()
        opener.open.return_value.__enter__.return_value.read.return_value = b'{}'
        with mock.patch.dict(os.environ, {'HTTP_PROXY': 'http://untrusted.invalid',
                                         'HTTPS_PROXY': 'http://untrusted.invalid'}), \
             mock.patch.object(fresh, 'build_opener', return_value=opener) as build:
            self.assertEqual(fresh.request('https://panel.invalid'), b'{}')
            self.assertEqual(build.call_args.args[0].proxies, {})
            self.assertIsInstance(build.call_args.args[1], fresh.NoRedirect)
            opener.open.return_value.__enter__.return_value.read.return_value = b'x' * (fresh.MAX_BYTES + 1)
            with self.assertRaises(ValueError):
                fresh.request('https://panel.invalid')
        with self.assertRaises(ValueError):
            fresh.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other.invalid')

    def test_fresh_directory_install_generates_only_own_units_and_first_report(self):
        units = self.base / 'systemd'
        units.mkdir()
        active = self.base / 'active-systemd'
        active.mkdir()
        protected = {'config.json': self.config.read_bytes(),
                     'gost.json': b'preserved runtime', 'gost': b'preserved agent'}
        for name, data in protected.items():
            if name != 'config.json':
                (self.root / name).write_bytes(data)
        commands = []
        def systemctl(command, **kwargs):
            commands.append(command)
            if command == ['systemctl', 'start', self.module.UNIT_NAME + '.service']:
                self.assertTrue(self.module.locked_once(self.root)['reported'])
            return subprocess.CompletedProcess(command, 0, b'', b'')
        with mock.patch.object(self.module, 'UNIT_DIR', units), \
             mock.patch.object(self.module, 'SYSTEMD_ACTIVE', active), \
             mock.patch.object(self.module.subprocess, 'run', side_effect=systemctl):
            result = self.module.install(self.root)
        self.assertTrue(result['installed'])
        self.assertFalse(result['agentRestarted'])
        self.assertEqual(len(self.posts), 1)
        self.assertEqual(sorted(p.name for p in units.iterdir()),
                         [self.module.UNIT_NAME + '.service', self.module.UNIT_NAME + '.timer'])
        unit = (units / (self.module.UNIT_NAME + '.service')).read_text()
        timer = (units / (self.module.UNIT_NAME + '.timer')).read_text()
        self.assertIn('--once --root ' + str(self.root), unit)
        self.assertIn('OnUnitInactiveSec=5s', timer)
        self.assertNotIn(self.identity['secret'], unit + timer)
        self.assertFalse(any('flux-panel-agent' in ' '.join(c) for c in commands))
        for name, data in protected.items():
            self.assertEqual((self.root / name).read_bytes(), data)
        self.assertEqual((self.root / 'airport_address_reporter.py').stat().st_mode & 0o777, 0o700)
        self.assertEqual(self.config.stat().st_mode & 0o777, 0o600)
        # Disabling a previously installed component skips observations, keeping its timer
        # available for a subsequent explicit re-enable without touching the relay.
        hashes = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in units.iterdir()}
        self.policy['enabled'] = False
        self.assertEqual(self.module.locked_once(self.root)['status'], 'disabled')
        self.assertEqual(hashes, {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in units.iterdir()})
        if shutil.which('systemd-analyze'):
            checked = subprocess.run(['systemd-analyze', 'verify',
                str(units / (self.module.UNIT_NAME + '.service')),
                str(units / (self.module.UNIT_NAME + '.timer'))], capture_output=True, text=True, timeout=10)
            self.assertEqual(checked.returncode, 0, checked.stderr)

    def test_disabled_or_old_panel_install_leaves_no_units(self):
        units = self.base / 'systemd'
        units.mkdir()
        with mock.patch.object(self.module, 'UNIT_DIR', units), \
             mock.patch.object(self.module.subprocess, 'run') as run:
            self.policy['enabled'] = False
            self.assertFalse(self.module.install(self.root)['installed'])
            self.old_panel = True
            self.assertFalse(self.module.install(self.root)['installed'])
            run.assert_not_called()
        self.assertEqual(list(units.iterdir()), [])
        self.assertFalse((self.root / 'airport_address_reporter.py').exists())

    def test_preinstalled_standby_can_be_enabled_online_without_installing_again(self):
        units = self.base / 'systemd'
        units.mkdir()
        active = self.base / 'active-systemd'
        active.mkdir()
        self.policy = {'enabled': False, 'observerInstallable': True, 'sourceMode': 'off'}
        with mock.patch.object(self.module, 'UNIT_DIR', units), \
             mock.patch.object(self.module, 'SYSTEMD_ACTIVE', active), \
             mock.patch.object(self.module.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)):
            self.assertTrue(self.module.install(self.root)['installed'])
        before = {p.name: p.read_bytes() for p in units.iterdir()}
        self.assertFalse(self.module.locked_once(self.root)['reported'])
        self.assertEqual(self.posts, [])
        self.policy.update(enabled=True, sourceMode='aws-imds')
        self.assertTrue(self.module.locked_once(self.root)['reported'])
        self.policy['enabled'] = False
        self.assertFalse(self.module.locked_once(self.root)['reported'])
        self.assertEqual(before, {p.name: p.read_bytes() for p in units.iterdir()})

    def test_blocked_but_opted_in_new_host_installs_then_recovers(self):
        units = self.base / 'systemd'
        units.mkdir()
        active = self.base / 'active-systemd'
        active.mkdir()
        self.policy = {'enabled': False, 'installEnabled': True, 'sourceMode': 'aws-imds'}
        def systemctl(command, **kwargs):
            if command == ['systemctl', 'start', self.module.UNIT_NAME + '.service']:
                self.assertEqual(self.module.locked_once(self.root)['status'], 'disabled')
            return subprocess.CompletedProcess(command, 0, b'', b'')
        with mock.patch.object(self.module, 'UNIT_DIR', units), \
             mock.patch.object(self.module, 'SYSTEMD_ACTIVE', active), \
             mock.patch.object(self.module.subprocess, 'run', side_effect=systemctl):
            self.assertTrue(self.module.install(self.root)['installed'])
        self.assertEqual(self.posts, [])
        self.assertTrue(all('/meta-data/' not in url and '/api/token' not in url for url, _, _ in self.calls))
        self.assertTrue((units / (self.module.UNIT_NAME + '.timer')).is_file())
        self.policy['enabled'] = True
        self.assertTrue(self.module.locked_once(self.root)['reported'])
        self.assertEqual(len(self.posts), 1)

    def test_overlap_skips_without_any_network(self):
        self.module.RUNTIME_DIR.mkdir(mode=0o700)
        lock = self.module.RUNTIME_DIR / 'once.lock'
        fd = os.open(lock, os.O_CREAT | os.O_RDWR, 0o600)
        try:
            self.module.fcntl.flock(fd, self.module.fcntl.LOCK_EX | self.module.fcntl.LOCK_NB)
            self.assertEqual(self.module.locked_once(self.root)['status'], 'busy')
            self.assertEqual(self.calls, [])
        finally:
            os.close(fd)


if __name__ == '__main__':
    unittest.main(verbosity=2)
