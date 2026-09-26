import unittest
import tempfile
import subprocess
from pathlib import Path
from http import HTTPStatus
from unittest.mock import patch

import panel_updater


class CurrentStateTest(unittest.TestCase):
    def setUp(self):
        self.previous = dict(panel_updater.STATE)

    def tearDown(self):
        panel_updater.STATE.clear()
        panel_updater.STATE.update(self.previous)

    def test_completed_task_reports_actual_installed_revision(self):
        panel_updater.STATE.update(
            state="completed", currentRevision="older", targetRevision="older"
        )
        with patch.object(panel_updater, "git_revision", return_value="installed"):
            status = panel_updater.current_state()
        self.assertEqual(status["currentRevision"], "installed")
        self.assertEqual(status["targetRevision"], "installed")

    def test_active_task_keeps_before_and_target_revisions(self):
        panel_updater.STATE.update(
            state="updating", currentRevision="before", targetRevision="target"
        )
        with patch.object(panel_updater, "git_revision") as revision:
            status = panel_updater.current_state()
        revision.assert_not_called()
        self.assertEqual(status["currentRevision"], "before")
        self.assertEqual(status["targetRevision"], "target")

    def test_update_migrates_environment_before_start_and_waits_for_health(self):
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory)
            (workspace / '.git').mkdir()
            (workspace / '.env').write_text('DB_PASSWORD=original\n', encoding='utf-8')
            calls = []
            def run(command, **kwargs):
                calls.append(command)
                if command[:2] == ['docker', 'compose']:
                    self.assertIn('MYSQL_IMAGE=mysql:5.7', (workspace / '.env').read_text())
                    self.assertIn('--wait', command)
                return subprocess.CompletedProcess(command, 0, '', '')
            with patch.object(panel_updater, 'WORKSPACE', workspace), \
                 patch.object(panel_updater, 'RELEASE_REF', '1.4.5'), \
                 patch.object(panel_updater, 'ensure_clean_workspace'), \
                 patch.object(panel_updater, 'create_backup', return_value=workspace / 'backup.sql'), \
                 patch.object(panel_updater, 'git_revision', return_value='before'), \
                 patch.object(panel_updater, 'fetch_release', return_value='after'), \
                 patch.object(panel_updater, 'run', side_effect=run):
                panel_updater.update_worker()
            self.assertEqual(panel_updater.STATE['state'], 'completed')
            self.assertEqual(calls[0][:3], ['git', 'reset', '--hard'])
            self.assertEqual(calls[-1][-3:], ['mysql', 'backend', 'frontend'])

    def test_failed_health_check_is_not_reported_as_success(self):
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory); (workspace / '.git').mkdir()
            (workspace / '.env').write_text('DB_PASSWORD=original\n', encoding='utf-8')
            def run(command, **kwargs):
                return subprocess.CompletedProcess(command, 1 if command[0] == 'docker' else 0, '', '')
            with patch.object(panel_updater, 'WORKSPACE', workspace), \
                 patch.object(panel_updater, 'RELEASE_REF', '1.4.5'), \
                 patch.object(panel_updater, 'ensure_clean_workspace'), \
                 patch.object(panel_updater, 'create_backup', return_value=workspace / 'backup.sql'), \
                 patch.object(panel_updater, 'git_revision', return_value='before'), \
                 patch.object(panel_updater, 'fetch_release', return_value='after'), \
                 patch.object(panel_updater, 'run', side_effect=run):
                panel_updater.update_worker()
            self.assertEqual(panel_updater.STATE['state'], 'failed')

    def test_second_request_is_rejected_while_first_worker_is_still_queued(self):
        handler = panel_updater.UpdateHandler.__new__(panel_updater.UpdateHandler)
        handler.path = '/update'
        panel_updater.STATE.update(state='queued')
        with patch.object(handler, 'authorized', return_value=True), \
             patch.object(handler, 'write_json') as response, \
             patch.object(panel_updater.threading, 'Thread') as worker:
            handler.do_POST()
        self.assertEqual(response.call_args.args[0], HTTPStatus.CONFLICT)
        worker.assert_not_called()


if __name__ == "__main__":
    unittest.main()
