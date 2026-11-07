"""The backend Docker context contains only springboot-backend, not its parent."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ObserverPackagingTest(unittest.TestCase):
    def test_docker_local_resource_exactly_matches_reviewed_collector(self):
        source = (ROOT / 'node-runtime/airport_address_reporter.py').read_bytes()
        bundled = (ROOT / 'springboot-backend/src/main/resources/node-runtime/airport_address_reporter.py').read_bytes()
        self.assertEqual(source, bundled)
        self.assertNotIn(b'\r\n', source)


if __name__ == '__main__':
    unittest.main()
