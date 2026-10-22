import copy
import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'node-runtime'))
import ddns_runtime_sidecar as sidecar


def desired(domain='old.example.com', token='private-provider-token', revision='a' * 64, enabled=True):
    return {'schemaVersion': 1, 'revision': revision, 'ddns': {'enabled': enabled,
        'provider': 'cloudflare', 'apiToken': token, 'recordName': domain, 'intervalSeconds': 60}}


class Network:
    def __init__(self):
        self.runtime = desired()
        self.panel_error = None
        self.calls = []
        self.records = {}
        self.duplicates = False

    def request(self, url, headers, method='GET', body=None):
        self.calls.append((url, copy.deepcopy(headers), method, copy.deepcopy(body)))
        if url == 'https://panel.example/api/v1/node/runtime-config':
            assert headers['Authorization'] == 'Bearer private-node-secret'
            if self.panel_error:
                raise self.panel_error
            return copy.deepcopy(self.runtime)
        assert url.startswith(sidecar.CF + '/')
        assert headers['Authorization'] == 'Bearer ' + self.runtime['ddns']['apiToken']
        path = urlsplit(url)
        query = parse_qs(path.query)
        if path.path.endswith('/zones'):
            return {'success': True, 'result': [{'id': '1' * 32}]}
        if method == 'GET':
            key = (query['name'][0], query['type'][0])
            row = self.records.get(key)
            records = [copy.deepcopy(row)] if row else []
            if self.duplicates:
                records += records
            return {'success': True, 'result': records}
        assert method in ('POST', 'PATCH')
        row = {**body, 'id': '2' * 32}
        self.records[(body['name'], body['type'])] = row
        return {'success': True, 'result': row}


class SidecarTests(unittest.TestCase):
    def setUp(self):
        self.root = Path('/private-test-root')
        self.store = {str(self.root / 'config.json'): {'addr': 'https://panel.example', 'secret': 'private-node-secret'}}
        self.writes = []
        self.network = Network()
        self.legacy = True
        self.ip_calls = []
        self.clock = 123

    def read(self, path):
        if str(path) not in self.store:
            raise FileNotFoundError()
        return copy.deepcopy(self.store[str(path)])

    def write(self, path, value):
        self.writes.append(str(path))
        self.store[str(path)] = copy.deepcopy(value)

    def detect(self, version):
        self.ip_calls.append(version)
        return '8.8.8.8' if version == 4 else '2001:4860:4860::8888'

    def runner(self, guard=None, writer=None):
        return sidecar.Sidecar(self.root, request=self.network.request, read=self.read,
            write=writer or self.write, is_legacy=guard or (lambda _: self.legacy),
            detect_ip=self.detect, now=lambda: self.clock)

    def mutating_requests(self):
        return [c for c in self.network.calls if c[2] != 'GET']

    def test_check_cannot_write_files_or_dns(self):
        result = self.runner().run(False)
        self.assertEqual(result['recordsPending'], 2)
        self.assertEqual(self.writes, [])
        self.assertEqual(self.mutating_requests(), [])
        self.assertEqual(set(self.store), {str(self.root / 'config.json')})

    def test_sync_persists_separate_cache_and_preserves_agent_config(self):
        original = copy.deepcopy(self.store[str(self.root / 'config.json')])
        result = self.runner().run(True)
        self.assertEqual(result['recordsChanged'], 2)
        self.assertEqual(self.store[str(self.root / 'config.json')], original)
        self.assertEqual(set(self.writes), {str(self.root / 'runtime-ddns-sidecar.json')})
        self.assertNotIn('private-', json.dumps(result))
        self.assertEqual(len(self.mutating_requests()), 2)

    def test_first_deployment_refuses_drift_without_any_dns_write(self):
        with self.assertRaises(sidecar.Refused):
            self.runner().run(True, require_no_dns_change=True)
        self.assertEqual(self.mutating_requests(), [])
        saved = self.store[str(self.root / 'runtime-ddns-sidecar.json')]
        self.assertNotIn('lastCheckedAt', saved)

    def test_first_deployment_checks_both_records_even_with_recent_cache(self):
        self.runner().run(True)
        self.network.calls.clear()
        result = self.runner().run(True, require_no_dns_change=True)
        self.assertEqual(result['recordsChecked'], 2)
        self.assertEqual(result['recordsChanged'], 0)
        self.assertEqual(self.mutating_requests(), [])
        self.network.records[('old.example.com', 'AAAA')]['content'] = '2001:4860:4860::8844'
        with self.assertRaises(sidecar.Refused):
            self.runner().run(True, require_no_dns_change=True)
        self.assertEqual(self.mutating_requests(), [])

    def test_domain_and_token_hot_change_uses_latest_config(self):
        self.runner().run(True)
        self.network.calls.clear()
        self.network.runtime = desired('new.example.com', 'new-private-token', 'b' * 64)
        result = self.runner().run(True)
        self.assertEqual(result['recordsChanged'], 2)
        for url, headers, method, body in self.mutating_requests():
            self.assertEqual(body['name'], 'new.example.com')
            self.assertEqual(headers['Authorization'], 'Bearer new-private-token')

    def test_disable_clears_cached_credential_and_skips_all_dns(self):
        self.runner().run(True)
        self.network.runtime = desired(enabled=False, revision='c' * 64)
        self.network.calls.clear()
        self.ip_calls.clear()
        result = self.runner().run(True)
        self.assertFalse(result['ddnsEnabled'])
        saved = self.store[str(self.root / 'runtime-ddns-sidecar.json')]
        self.assertEqual(saved['configuration']['ddns']['apiToken'], '')
        self.assertEqual(len(self.network.calls), 1)
        self.assertEqual(self.ip_calls, [])

    def test_transient_panel_failure_reuses_last_good_identity_bound_cache(self):
        self.runner().run(True)
        self.network.panel_error = sidecar.FetchFailed(503)
        self.clock += 61
        result = self.runner().run(True)
        self.assertTrue(result['usedSavedConfiguration'])
        self.assertEqual(result['recordsChanged'], 0)

    def test_revoked_node_clears_cache_and_cannot_fallback_later(self):
        self.runner().run(True)
        self.network.panel_error = sidecar.FetchFailed(401)
        self.network.calls.clear()
        with self.assertRaises(sidecar.Refused):
            self.runner().run(True)
        saved = self.store[str(self.root / 'runtime-ddns-sidecar.json')]
        self.assertTrue(saved['revoked'])
        self.assertNotIn('configuration', saved)
        self.assertEqual(len(self.network.calls), 1)
        self.network.panel_error = sidecar.FetchFailed(503)
        with self.assertRaises(sidecar.Refused):
            self.runner().run(True)

    def test_native_and_unknown_ownership_never_pull_or_update(self):
        for guard in (lambda _: False, lambda _: (_ for _ in ()).throw(sidecar.Refused('unknown'))):
            with self.assertRaises(sidecar.Refused):
                self.runner(guard=guard).run(True)
        self.assertEqual(self.network.calls, [])
        self.assertEqual(self.writes, [])

    def test_native_handoff_recheck_prevents_cloudflare_mutation(self):
        def guard(_):
            return not any(c[0].startswith(sidecar.CF) for c in self.network.calls)
        with self.assertRaises(sidecar.Refused):
            self.runner(guard=guard).run(True)
        self.assertEqual(self.mutating_requests(), [])

    def test_disk_failure_keeps_cloudflare_unchanged(self):
        def denied(*args):
            raise OSError('test disk full')
        with self.assertRaises(OSError):
            self.runner(writer=denied).run(True)
        self.assertEqual(len(self.network.calls), 1)
        self.assertEqual(self.mutating_requests(), [])

    def test_duplicate_records_refused_and_existing_ttl_proxy_preserved(self):
        row = {'id': '2' * 32, 'name': 'old.example.com', 'type': 'A', 'content': '1.1.1.1', 'ttl': 300, 'proxied': True}
        self.network.records[('old.example.com', 'A')] = row
        self.network.duplicates = True
        with self.assertRaises(sidecar.Refused):
            self.runner().run(True)
        self.assertEqual(self.mutating_requests(), [])
        self.network.duplicates = False
        self.runner().run(True)
        actual = self.network.records[('old.example.com', 'A')]
        self.assertEqual(actual['ttl'], 300)
        self.assertTrue(actual['proxied'])

    def test_changed_node_identity_does_not_use_another_nodes_cache(self):
        self.runner().run(True)
        self.store[str(self.root / 'config.json')]['addr'] = 'https://different-panel.example'
        def unavailable(*args):
            raise sidecar.FetchFailed(503)
        runner = self.runner()
        runner.request = unavailable
        with self.assertRaises(sidecar.Refused):
            runner.run(True)

    def test_invalid_settings_fail_before_disk_or_dns(self):
        for mutation in ({'apiToken': 'bad\nheader'}, {'recordName': 'bad/name'}, {'intervalSeconds': False}):
            self.network.runtime = desired()
            self.network.runtime['ddns'].update(mutation)
            with self.assertRaises(sidecar.Refused):
                self.runner().run(True)
        self.assertEqual(self.writes, [])
        self.assertEqual(self.mutating_requests(), [])

    def test_version_probe_distinguishes_legacy_native_unknown(self):
        probes = [(subprocess.CompletedProcess([], 0, '1.4.16-enhanced\n', ''), False),
                  (subprocess.CompletedProcess([], 2, '', 'flag provided but not defined: -panel-agent-version'), True)]
        for process, expected in probes:
            with patch.object(sidecar.subprocess, 'run', return_value=process):
                self.assertEqual(sidecar.legacy_agent(self.root), expected)
        for failure in (subprocess.TimeoutExpired('gost', 5), OSError('not executable')):
            with patch.object(sidecar.subprocess, 'run', side_effect=failure):
                with self.assertRaises(sidecar.Refused):
                    sidecar.legacy_agent(self.root)
        with patch.object(sidecar.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, 'unexpected', '')):
            with self.assertRaises(sidecar.Refused):
                sidecar.legacy_agent(self.root)

    def test_interval_is_respected_but_configuration_change_is_immediate(self):
        self.network.runtime['ddns']['intervalSeconds'] = 120
        self.runner().run(True)
        self.network.calls.clear()
        self.clock += 60
        result = self.runner().run(True)
        self.assertTrue(result['intervalPending'])
        self.assertEqual(len(self.network.calls), 1)
        self.network.runtime = desired('new.example.com', revision='b' * 64)
        result = self.runner().run(True)
        self.assertEqual(result['recordsChanged'], 2)
        self.network.runtime['ddns']['intervalSeconds'] = 30
        self.network.runtime['revision'] = 'c' * 64
        result = self.runner().run(True)
        self.assertEqual(result['effectiveIntervalSeconds'], 60)


if __name__ == '__main__':
    unittest.main()
