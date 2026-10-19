"""Keep release assets, deployment defaults and generated Agent commands aligned."""
from pathlib import Path
import re
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'updater'))
from env_migration import migrate_env


class ReleaseVersionContractTest(unittest.TestCase):
    def test_deployment_defaults_match_the_published_release(self):
        workflow = (ROOT / '.github/workflows/docker-build.yml').read_text(encoding='utf-8')
        version = re.search(r'^  VERSION: "([\d.]+)"$', workflow, re.M).group(1)
        files = [
            'install.sh', 'backend_install.sh', 'panel_install.sh', 'upgrade_from_original.sh',
            'docker-compose.yml', 'docker-compose-v4.yml', 'docker-compose-v6.yml',
            'updater/panel_updater.py', 'vite-frontend/src/config/site.ts',
            'springboot-backend/src/main/resources/application.yml',
            'springboot-backend/src/main/java/com/admin/service/impl/NodeServiceImpl.java',
            'springboot-backend/src/main/java/com/admin/service/impl/VpsSshServiceImpl.java',
            'go-gost/main.go',
        ]
        pattern = r'(?:AGENT_RELEASE|REPO_REF|AGENT_INSTALL_RELEASE|VPS_BACKEND_INSTALL_RELEASE|PANEL_RELEASE_REF|agent\.install\.release|vps\.backend-install\.release):[-]?([\d.]+)|"BRANCH", "([\d.]+)"|const VERSION = "([\d.]+)"|const panelAgentVersion = "([\d.]+)-enhanced"'
        for name in files:
            with self.subTest(file=name):
                matches = re.findall(pattern, (ROOT / name).read_text(encoding='utf-8'))
                self.assertTrue(matches, 'Expected at least one release default')
                self.assertEqual({next(x for x in match if x) for match in matches}, {version})
        urls = re.findall(r'/releases/download/([\d.]+)/', (ROOT / 'README.md').read_text(encoding='utf-8'))
        self.assertTrue(urls)
        self.assertEqual(set(urls), {version})

    def test_upgrade_replaces_old_agent_pin_without_changing_credentials(self):
        workflow = (ROOT / '.github/workflows/docker-build.yml').read_text(encoding='utf-8')
        version = re.search(r'^  VERSION: "([\d.]+)"$', workflow, re.M).group(1)
        with tempfile.TemporaryDirectory() as directory:
            env = Path(directory) / '.env'
            env.write_text('DB_PASSWORD=keep-db\nJWT_SECRET=keep-jwt\n'
                           'PANEL_RELEASE_REF=1.4.6\nVPS_BACKEND_INSTALL_RELEASE=1.4.6\n'
                           'AGENT_INSTALL_RELEASE=1.4.6\n', encoding='utf-8')
            migrate_env(env, version)
            updated = env.read_text(encoding='utf-8')
            for key in ('PANEL_RELEASE_REF', 'VPS_BACKEND_INSTALL_RELEASE', 'AGENT_INSTALL_RELEASE'):
                self.assertIn(f'{key}={version}\n', updated)
            self.assertIn('DB_PASSWORD=keep-db\n', updated)
            self.assertIn('JWT_SECRET=keep-jwt\n', updated)
            migrate_env(env, version)
            self.assertEqual(updated, env.read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
