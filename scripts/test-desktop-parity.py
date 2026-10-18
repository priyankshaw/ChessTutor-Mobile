"""Run desktop teaching regressions against the actual bundled mobile copies."""
from pathlib import Path
from dataclasses import dataclass
import sys
import types
import unittest

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / "native-android/app/src/main/python"))
sys.path.append(str(root / "tests/desktop-support"))
# Live endpoint regressions exercise the extracted native body, without FastAPI.
import backend
from backend import mobile_endpoints
from mobile_errors import HTTPException

# Endpoint regressions use the native exception contract, without importing a web server.
fastapi = types.ModuleType("fastapi")
fastapi.HTTPException = HTTPException
sys.modules["fastapi"] = fastapi

@dataclass
class EvaluateMoveRequest:
    fen_before: str
    move_uci: str
    start_fen: str | None = None
    history_san: list[str] | None = None
    depth: int = 14
    player_elo: int | None = None
    prev_was_opponent_error: bool = False

mobile_endpoints.EvaluateMoveRequest = EvaluateMoveRequest
@dataclass
class CheckSolutionRequest:
    fen: str
    move_uci: str
    best_move_uci: str

mobile_endpoints.CheckSolutionRequest = CheckSolutionRequest
mobile_endpoints.evaluator = None
backend.main = mobile_endpoints
sys.modules["backend.main"] = mobile_endpoints
test_root = root / "tests/desktop-snapshot"
suite = unittest.TestSuite()
for path in sorted(test_root.glob("test_*.py")):
    if path.name in {"test_opponent.py", "test_launcher.py", "test_request_validation.py"}:
        continue  # Desktop HTTP/launcher/Pydantic checks; native boundaries are tested in tests.test_runtime.
    suite.addTests(unittest.defaultTestLoader.discover(str(test_root), pattern=path.name))
result = unittest.TextTestRunner(verbosity=1).run(suite)
raise SystemExit(not result.wasSuccessful())
