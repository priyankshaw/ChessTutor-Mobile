"""Synced algorithms, generated endpoints and regression fixtures stay one snapshot."""
import hashlib
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class UpstreamSnapshotTests(unittest.TestCase):
    def test_bundled_algorithms_and_regressions_match_manifest(self):
        manifest = json.loads((ROOT / "upstream-manifest.json").read_text())
        files = manifest["bundled_files"]
        self.assertIn("native-android/app/src/main/python/backend/mobile_endpoints.py", files)
        self.assertIn("tests/desktop-snapshot/test_position_context.py", files)
        for relative, expected in files.items():
            with self.subTest(file=relative):
                self.assertEqual(hashlib.sha256((ROOT / relative).read_bytes()).hexdigest(), expected)
