import unittest
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


if __name__ == "__main__":
    unittest.main()
