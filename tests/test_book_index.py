import json
import hashlib
from pathlib import Path
import sqlite3
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'native-android/app/src/main/python'))
from mobile_book import load_book


class BookIndexTests(unittest.TestCase):
    def test_every_desktop_opening_and_position_is_retained(self):
        source = (ROOT.parent / 'ChessTutor/backend/data/openings_compiled.json').read_bytes()
        manifest = json.loads((ROOT / 'upstream-manifest.json').read_text())
        self.assertEqual(hashlib.sha256(source).hexdigest(), manifest['files']['backend/data/openings_compiled.json'],
                         'Desktop book changed after sync; test the matching snapshot.')
        original = json.loads(source)
        path = ROOT / 'native-android/app/src/main/python/backend/data/openings.sqlite'
        database = sqlite3.connect(path)
        try:
            for table, expected in original.items():
                if isinstance(expected, list):
                    expected = set(expected)
                self.assertEqual(database.execute(f'SELECT COUNT(*) FROM {table}').fetchone()[0], len(expected))
                for key, value in database.execute(f'SELECT key, value FROM {table}'):
                    self.assertIn(key, expected)
                    if isinstance(expected, dict):
                        self.assertEqual(json.loads(value), expected[key])
            maps = load_book(path)
            self.assertEqual(maps[0]['e4'], original['openings']['e4'])
            self.assertIn('e4 e5', maps[1])
            self.assertNotIn('invalid position', maps[3])
            maps[0].store.connection.close()
        finally:
            database.close()
