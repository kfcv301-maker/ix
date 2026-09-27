"""Protect the stable backend entry URL and its committed script checksum."""
import hashlib
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
URL = 'https://raw.githubusercontent.com/kfcv301-maker/ix/main/backend_install.sh'


class BackendInstallerContractTest(unittest.TestCase):
    def test_stable_entry_url_is_present_in_template_and_public_instructions(self):
        template = (ROOT / 'springboot-backend/src/main/java/com/admin/service/impl/VpsSshServiceImpl.java').read_text(encoding='utf-8')
        self.assertIn('String installerUrl = "' + URL + '";', template)
        self.assertIn('curl -fsSL ' + URL + ' | sudo bash', (ROOT / 'README.md').read_text(encoding='utf-8'))

    def test_companion_checksum_matches_exact_script_bytes(self):
        script = (ROOT / 'backend_install.sh').read_bytes()
        self.assertNotIn(b'\r\n', script, 'Installer is served with LF bytes')
        self.assertEqual((ROOT / 'backend_install.sh.sha256').read_text().strip(),
                         hashlib.sha256(script).hexdigest() + '  backend_install.sh')


if __name__ == '__main__':
    unittest.main()
