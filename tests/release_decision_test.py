"""Exercise the actual workflow decision script without GitHub writes or secrets."""
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


PROJECT = Path(__file__).resolve().parents[1]
lines = (PROJECT / '.github/workflows/docker-build.yml').read_text(encoding='utf-8').splitlines()
start = next(i for i, line in enumerate(lines) if line.strip() == '- id: release')
start = next(i for i in range(start, len(lines)) if lines[i].strip() == 'run: |') + 1
end = start
while end < len(lines) and (not lines[end].strip() or lines[end].startswith('          ')):
    end += 1
SCRIPT = textwrap.dedent('\n'.join(lines[start:end]))
MOCKS = '''set -eu
git() { if [[ "$FAKE_TAG" == absent ]]; then return 1; fi; printf '%s\\n' "$FAKE_SHA"; }
gh() {
  if [[ "$FAKE_RELEASE" == absent ]]; then return 1; fi
  if [[ "$*" == *targetCommitish* ]]; then printf '%s\\n' "$FAKE_SHA"; else printf '%s\\n' "$FAKE_RELEASE"; fi
}
'''


class ReleaseDecisionTest(unittest.TestCase):
    def check_case(self, release, tag, sha, code, expected=None):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'github-output'
            output.touch()
            env = dict(os.environ, GITHUB_REF='refs/heads/main', GITHUB_EVENT_NAME='push',
                       GITHUB_REPOSITORY='kfcv301-maker/ix', GITHUB_SHA='test-source', VERSION='1.4.5',
                       DOCKER_HUB_USERNAME='', DOCKER_HUB_TOKEN='', GITHUB_OUTPUT=output.as_posix(),
                       FAKE_RELEASE=release, FAKE_TAG=tag, FAKE_SHA=sha)
            result = subprocess.run([os.environ.get('RELEASE_TEST_BASH', 'bash'), '-c', MOCKS + SCRIPT],
                                    cwd=PROJECT, env=env, capture_output=True, text=True)
            self.assertEqual(code, result.returncode, result.stderr)
            values = output.read_text(encoding='utf-8')
            self.assertIn('publish_images=false', values)
            if expected:
                self.assertIn(expected, values)

    def test_source_release_does_not_need_registry_credentials(self):
        self.check_case('absent', 'absent', 'test-source', 0, 'publish_release=true')

    def test_published_version_cannot_be_replaced(self):
        self.check_case('false', 'exists', 'test-source', 0, 'publish_release=false')

    def test_existing_tag_must_match_the_source(self):
        self.check_case('absent', 'exists', 'different-source', 1)

    def test_matching_draft_can_be_resumed(self):
        self.check_case('true', 'absent', 'test-source', 0, 'publish_release=true')

    def test_mismatched_draft_cannot_mix_old_source_and_new_assets(self):
        self.check_case('true', 'absent', 'different-source', 1)


if __name__ == '__main__':
    unittest.main()
