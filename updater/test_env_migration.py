from pathlib import Path
import tempfile
import unittest
from env_migration import migrate_env


class EnvironmentMigrationTest(unittest.TestCase):
    def test_old_environment_preserves_database_and_uses_persistent_admin_bootstrap(self):
        with tempfile.TemporaryDirectory() as directory:
            env = Path(directory) / '.env'
            env.write_text('DB_PASSWORD="secret $value"\nJWT_SECRET=unchanged\n# local comment\n', encoding='utf-8')
            migrate_env(env, '1.4.5')
            migrated = env.read_text(encoding='utf-8')
            self.assertIn('MYSQL_IMAGE=mysql:5.7\n', migrated)
            self.assertIn('DB_PASSWORD="secret $value"\n', migrated)
            self.assertIn('JWT_SECRET=unchanged\n', migrated)
            self.assertNotIn('PANEL_INITIAL_ADMIN_PASSWORD=', migrated)
            self.assertIn('# local comment', migrated)
            migrate_env(env, '1.4.5')
            self.assertEqual(env.read_text(encoding='utf-8'), migrated)

    def test_new_mysql_and_explicit_admin_credentials_are_retained_and_release_advances(self):
        with tempfile.TemporaryDirectory() as directory:
            env = Path(directory) / '.env'
            env.write_text('MYSQL_IMAGE=mysql:8.4\nPANEL_INITIAL_ADMIN_USERNAME=operator\n'
                           'PANEL_INITIAL_ADMIN_PASSWORD=original-long-password\n'
                           'PANEL_RELEASE_REF=1.4.5\nAGENT_INSTALL_RELEASE=1.4.5\n', encoding='utf-8')
            migrate_env(env, '1.4.6')
            migrated = env.read_text(encoding='utf-8')
            self.assertIn('MYSQL_IMAGE=mysql:8.4', migrated)
            self.assertIn('PANEL_INITIAL_ADMIN_PASSWORD=original-long-password', migrated)
            self.assertIn('PANEL_RELEASE_REF=1.4.6', migrated)
            self.assertIn('AGENT_INSTALL_RELEASE=1.4.6', migrated)

    def test_invalid_release_does_not_change_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            env = Path(directory) / '.env'
            original = 'DB_PASSWORD=unchanged\n'
            env.write_text(original, encoding='utf-8')
            with self.assertRaises(ValueError): migrate_env(env, 'main')
            self.assertEqual(env.read_text(encoding='utf-8'), original)
