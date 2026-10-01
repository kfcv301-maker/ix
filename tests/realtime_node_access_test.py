"""Exercise the actual mapper query against owned and assigned node fixtures."""

import sqlite3
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


MAPPER = Path(__file__).resolve().parents[1] / "springboot-backend/src/main/resources/mapper/UserMapper.xml"


def query(statement_id):
    root = ET.parse(MAPPER).getroot()
    statement = root.find(f"select[@id='{statement_id}']")
    assert statement is not None
    return " ".join(statement.itertext()).replace("#{userId}", ":userId").replace("#{now}", ":now")


class RealtimeNodeAccessTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript("""
            CREATE TABLE node (id INTEGER PRIMARY KEY, name TEXT, owner_user_id INTEGER);
            CREATE TABLE tunnel (id INTEGER PRIMARY KEY, in_node_id INTEGER, out_node_id INTEGER,
                owner_user_id INTEGER, status INTEGER);
            CREATE TABLE user_tunnel (user_id INTEGER, tunnel_id INTEGER, status INTEGER, exp_time INTEGER);
            CREATE TABLE tunnel_entry_node (tunnel_id INTEGER, node_id INTEGER, status INTEGER);
            INSERT INTO node VALUES
                (1, 'self', 42), (2, 'entry', NULL), (3, 'exit', NULL),
                (4, 'second entry', NULL), (5, 'disabled grant', NULL),
                (6, 'expired grant', NULL), (7, 'other owner', 99),
                (8, 'disabled tunnel', NULL), (9, 'disabled entry', NULL),
                (10, 'unrelated', 99);
            INSERT INTO tunnel VALUES
                (10, 2, 3, NULL, 1), (11, 5, 5, NULL, 1),
                (12, 6, 6, NULL, 1), (13, 7, 7, 99, 1),
                (14, 8, 8, NULL, 0);
            INSERT INTO user_tunnel VALUES
                (42, 10, 1, 2000), (42, 11, 0, 2000),
                (42, 12, 1, 500), (42, 13, 1, 2000),
                (42, 14, 1, 2000);
            INSERT INTO tunnel_entry_node VALUES
                (10, 2, 1), (10, 4, 1), (10, 9, 0);
        """)

    def tearDown(self):
        self.db.close()

    def test_summary_includes_owned_and_active_assigned_nodes_once(self):
        rows = self.db.execute(query("getRealtimeNodes"), {"userId": 42, "now": 1000}).fetchall()
        self.assertEqual(rows, [(1, "self"), (2, "entry"), (3, "exit"), (4, "second entry")])
        self.assertEqual(self.db.execute(query("getRealtimeNodes"), {"userId": 77, "now": 1000}).fetchall(), [])

    def test_expired_assignment_stops_exposing_nodes(self):
        rows = self.db.execute(query("getRealtimeNodes"), {"userId": 42, "now": 2000}).fetchall()
        self.assertEqual(rows, [(1, "self")])

    def test_socket_refreshes_at_the_next_grant_expiry(self):
        row = self.db.execute(query("getNextRealtimeGrantExpiry"), {"userId": 42, "now": 1000}).fetchone()
        self.assertEqual(row, (2000,))
        row = self.db.execute(query("getNextRealtimeGrantExpiry"), {"userId": 42, "now": 2000}).fetchone()
        self.assertEqual(row, (None,))

    def test_node_management_remains_owner_only(self):
        rows = self.db.execute(query("getAccessibleNodeIds"), {"userId": 42}).fetchall()
        self.assertEqual(rows, [(1,)])


if __name__ == "__main__":
    unittest.main()
