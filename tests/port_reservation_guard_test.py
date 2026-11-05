import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]
if os.name == 'nt':
    sys.modules.setdefault('fcntl', Mock())
    # Production runs only on Linux; emulate its filesystem flags in unit tests.
    os.O_NOFOLLOW = 0
    os.fchmod = lambda fd, mode: None
spec = importlib.util.spec_from_file_location('port_guard', ROOT / 'node-runtime/port_reservation_guard.py')
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class PortGuardTest(unittest.TestCase):
    def test_preserves_external_reservations_and_compresses_ranges(self):
        current = guard.parse_ranges('22,80,100-102')
        merged = guard.merge_reservations(current, {101, 38228, 54348}, 32768, 60999)
        self.assertTrue(current <= merged)
        self.assertEqual(guard.parse_ranges(guard.format_ranges(merged)), merged)
        self.assertEqual('22,80,100-102,38228,54348', guard.format_ranges(merged))

    def test_never_exhausts_outbound_port_space(self):
        with self.assertRaises(ValueError):
            guard.merge_reservations({22}, set(range(1000, 65536)), 32768, 60999)
        # Do not undo pre-existing administrator settings in an idempotent run.
        self.assertEqual({40000}, guard.merge_reservations({40000}, {40000}, 40000, 40001))

    def test_malformed_ports_fail_closed(self):
        for value in ([True], [0], [65536], ['123'], '123'):
            with self.assertRaises(ValueError):
                guard.port_list(value)
        for value in ('3-1', '-1', '1-65536', 'bad'):
            with self.assertRaises(ValueError):
                guard.parse_ranges(value)

    def test_paused_and_both_address_families_are_protected(self):
        services = [{'addr': '[::]:54348', 'metadata': {'paused': True}},
                    {'addr': '0.0.0.0:38228'}, {'addr': ':37722'}]
        self.assertEqual({54348, 38228, 37722}, guard.local_ports({'services': services}))

    def test_panel_requests_identify_helper_and_support_legacy_bare_origin(self):
        response = Mock()
        response.read.return_value = b'{"schemaVersion":1,"reservedPorts":[38228]}'
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        opener = Mock(); opener.open.return_value = response
        with patch.object(guard.urllib.request, 'build_opener', return_value=opener):
            self.assertEqual({38228}, guard.fetch_ports({'addr': 'panel.invalid:6365', 'secret': 'synthetic'}))
        request = opener.open.call_args.args[0]
        self.assertEqual('http://panel.invalid:6365/api/v1/node/port-reservations', request.full_url)
        self.assertEqual('Lunaris-Port-Guard/1.0', request.get_header('User-agent'))
        self.assertEqual('Bearer synthetic', request.get_header('Authorization'))

    def run_sync(self, fetch):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            proc = root / 'proc'; proc.mkdir()
            (proc / 'ip_local_port_range').write_text('32768 60999')
            (proc / 'ip_local_reserved_ports').write_text('80,40001')
            fixtures = {'config.json': {'addr': 'https://test.invalid', 'secret': 'synthetic'},
                        'gost.json': {'services': [{'addr': '[::]:54348', 'metadata': {'paused': True}}]},
                        'port-guard-cache.json': {'reservedPorts': [38228]}}
            for name, data in fixtures.items():
                (root / name).write_text(json.dumps(data))
            with patch.object(guard, 'fetch_ports', fetch), patch.object(guard, 'read_json', lambda p: json.loads(p.read_text())):
                result = guard.sync(root, proc=proc)
                actual = guard.parse_ranges((proc / 'ip_local_reserved_ports').read_text())
                self.assertTrue({80, 40001, 38228, 54348} <= actual)
                self.assertFalse(result['agentRestarted'])
                return actual, result

    def test_old_or_unavailable_panel_keeps_local_and_saved_ports(self):
        actual, result = self.run_sync(Mock(side_effect=OSError('synthetic connection failure')))
        self.assertFalse(result['panelSynced'])
        self.assertEqual({80, 40001, 38228, 54348}, actual)

    def test_pending_panel_claim_protected_before_it_appears_locally(self):
        actual, result = self.run_sync(Mock(return_value={37722}))
        self.assertTrue(result['panelSynced'])
        self.assertIn(37722, actual)

    def test_embedded_installer_helpers_match_reviewed_source(self):
        source = (ROOT / 'node-runtime/port_reservation_guard.py').read_text(encoding='utf-8')
        for name in ('install.sh', 'node_install.sh'):
            text = (ROOT / name).read_text(encoding='utf-8')
            embedded = text.split("<<'PORT_GUARD_PY'\n", 1)[1].split('\nPORT_GUARD_PY', 1)[0]
            self.assertEqual(source.rstrip('\n'), embedded)


if __name__ == '__main__':
    unittest.main()
