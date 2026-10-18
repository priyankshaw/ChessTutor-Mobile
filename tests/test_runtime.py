import json
from pathlib import Path
import sys
import unittest
from unittest.mock import AsyncMock, Mock, patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "native-android/app/src/main/python"))
import chess
import mobile_runtime


class OfflineRuntimeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        mobile_runtime.initialize(str(ROOT.parent / "ChessTutor/backend/bin/stockfish.exe"))

    @classmethod
    def tearDownClass(cls):
        mobile_runtime.close()

    def call(self, path, body=None, method="POST", request_id="test"):
        return json.loads(mobile_runtime.request(request_id, json.dumps({"path": path, "body": body, "method": method})))

    def test_real_engine_and_phone_memory_budget(self):
        status = self.call("/api/status", method="GET")
        self.assertTrue(status["body"]["stockfish_ready"])
        config = mobile_runtime._evaluator.engine.protocol.config
        self.assertEqual(config["Hash"], 64)
        self.assertEqual(config["Threads"], 2)

    def test_actual_progress_callback(self):
        events = []
        class Reporter:
            def update(self, value):
                events.append(json.loads(value))
        result = json.loads(mobile_runtime.request('progress-test', json.dumps({
            'path': '/api/analyze', 'method': 'POST', 'body': {'pgn': '1. f3 e5 2. g4 Qh4# 0-1'}
        }), Reporter()))
        self.assertEqual(result['status'], 200)
        self.assertEqual(events[0]['completed_plies'], 0)
        self.assertEqual(events[-1]['completed_moves'], 2)
        self.assertEqual(events[-1]['completed_plies'], 4)

    def test_full_mating_game_review(self):
        result = self.call("/api/analyze", {"pgn": '1. f3 e5 2. g4 Qh4# 0-1'})
        self.assertEqual(result["status"], 200, result)
        data = result["body"]
        self.assertEqual(len(data["positions"]), 4)
        self.assertTrue(chess.Board(data["positions"][-1]["fen_after"]).is_checkmate())
        self.assertTrue(all(position["explanation"] for position in data["positions"]))
        self.assertIn("white", data["accuracy"])

    def test_invalid_pgn_fails_instead_of_partial_review(self):
        result = self.call("/api/analyze", {"pgn": "1. e4 e5 2. Bh6 *"})
        self.assertEqual(result["status"], 400)

    def test_native_pgn_parser_and_fen_validation(self):
        parsed = self.call("/api/parse-pgn", {"pgn": '[White "Alice"]\n\n1. e4 e5 *'})
        self.assertEqual(parsed["status"], 200)
        self.assertEqual(parsed["body"]["moves"], ["e2e4", "e7e5"])
        self.assertEqual(parsed["body"]["headers"]["White"], "Alice")
        self.assertEqual(self.call("/api/parse-pgn", {"pgn": "1. e4 e5 2. Bh6 *"})["status"], 400)
        self.assertEqual(self.call("/api/validate-fen", {"fen": "8/8/8/8/8/8/8/8 w - - 0 1"})["status"], 400)
        self.assertEqual(self.call("/api/validate-fen", {"fen": chess.STARTING_FEN})["status"], 200)

    def test_native_parser_does_not_need_stockfish_initialization(self):
        import subprocess
        script = '''import sys, json
sys.path.insert(0, sys.argv[1])
import mobile_runtime
print(mobile_runtime.request("parse-only", json.dumps({"path": "/api/parse-pgn", "method": "POST", "body": {"pgn": "1. e4 e5 *"}})))
'''
        reply = json.loads(subprocess.check_output([sys.executable, "-c", script, str(ROOT / "native-android/app/src/main/python")], text=True))
        self.assertEqual(reply["status"], 200, reply)
        self.assertEqual(reply["body"]["moves"], ["e2e4", "e7e5"])

    def test_live_move_and_retry(self):
        result = self.call("/api/evaluate-move", {"fen_before": chess.STARTING_FEN, "move_uci": "e2e4", "history_san": []})
        self.assertEqual(result["status"], 200, result)
        self.assertTrue(result["body"]["is_legal"])
        retry = self.call("/api/check-solution", {"fen": chess.STARTING_FEN, "move_uci": "e2e4", "best_move_uci": "e2e4"})
        self.assertTrue(retry["body"]["is_best"])

    def test_non_book_live_move_uses_desktop_comparison_helper(self):
        result = self.call("/api/evaluate-move", {"fen_before": chess.STARTING_FEN,
                           "move_uci": "a2a3", "history_san": []})
        self.assertEqual(result["status"], 200, result)
        self.assertGreaterEqual(result["body"]["classification"]["cp_loss"], 0)
        self.assertIsNone(result["body"]["eval"]["white_mate"])

    def test_live_custom_recapture_keeps_history_and_player_rating(self):
        from backend.engine import classifier
        from backend.engine.evaluator import StockfishEvaluator
        class FixtureEvaluator:
            def evaluate_position(self, board, **options):
                return StockfishEvaluator._heuristic_fallback(self, board)
        start = "7k/8/4p3/3p4/2P5/8/8/7K w - - 0 1"
        board = chess.Board(start); board.push_san("cxd5")
        with patch.object(mobile_runtime._endpoints, "evaluator", FixtureEvaluator()), \
                patch.object(classifier, "classify_move", wraps=classifier.classify_move) as classify:
            result = self.call("/api/evaluate-move", {"start_fen": start, "fen_before": board.fen(),
                "history_san": ["cxd5"], "move_uci": "e6d5", "player_elo": 831})
        self.assertEqual(result["status"], 200, result)
        self.assertTrue(classify.call_args.kwargs["is_recapture_move"])
        self.assertEqual(classify.call_args.kwargs["player_elo"], 831)
        self.assertEqual(len(classify.call_args.kwargs["board_before"].move_stack), 1)
        self.assertNotEqual(result["body"]["classification"]["classification"], "book")

    def test_desktop_depth_bounds_and_review_default_reach_the_engine(self):
        analyzer = Mock(analyze_game=AsyncMock(return_value={"positions": []}))
        endpoint = Mock(return_value={"is_legal": True})
        with patch.object(mobile_runtime, "_analyzer", analyzer), \
                patch.object(mobile_runtime._endpoints, "evaluate_move_endpoint", endpoint):
            self.assertEqual(self.call("/api/analyze", {"pgn": "1. e4 *"})["status"], 200)
            self.assertEqual(analyzer.analyze_game.call_args.kwargs["depth"], 16)
            for depth in (10, 14, 16, 22):
                self.assertEqual(self.call("/api/analyze", {"pgn": "1. e4 *", "depth": depth})["status"], 200)
                self.assertEqual(analyzer.analyze_game.call_args.kwargs["depth"], depth)
                result = self.call("/api/evaluate-move", {"fen_before": chess.STARTING_FEN, "move_uci": "e2e4", "depth": depth})
                self.assertEqual(result["status"], 200)
                self.assertEqual(endpoint.call_args.args[0].depth, depth)
            for depth in (8, 9, 23, True, 14.5):
                self.assertEqual(self.call("/api/analyze", {"pgn": "1. e4 *", "depth": depth})["status"], 400)
                self.assertEqual(self.call("/api/evaluate-move", {"fen_before": chess.STARTING_FEN, "move_uci": "e2e4", "depth": depth})["status"], 400)

    def test_custom_position_context_is_validated_before_evaluation(self):
        with patch.object(mobile_runtime._endpoints, "evaluate_move_endpoint") as endpoint:
            result = self.call("/api/evaluate-move", {"fen_before": chess.STARTING_FEN, "move_uci": "e2e4", "start_fen": "8/8/8/8/8/8/8/8 w - - 0 1"})
            self.assertEqual(result["status"], 400)
            endpoint.assert_not_called()

    def test_strength_control_and_legal_reply(self):
        for level in ("1800", "max"):
            result = self.call("/api/play/move", {"moves": ["e2e4"], "level": level, "think_time": 0.1, "game_id": "offline-" + level})
            self.assertEqual(result["status"], 200, result)
            board = chess.Board(); board.push_uci("e2e4")
            self.assertIn(chess.Move.from_uci(result["body"]["move_uci"]), board.legal_moves)

    def test_all_opening_data_available_locally(self):
        from backend.engine.book import OPENINGS_MAP, BOOK_FENS
        self.assertGreater(len(OPENINGS_MAP), 3000)
        self.assertGreater(len(BOOK_FENS), 1000)
        result = self.call("/api/openings/search?q=Italian&limit=3", method="GET")
        self.assertEqual(result["status"], 200, result)
        self.assertTrue(result["body"])

    def test_cancelled_request_never_searches(self):
        mobile_runtime.cancel("cancel-test")
        result = self.call("/api/analyze", {"pgn": "1. e4 e5 *"}, request_id="cancel-test")
        self.assertEqual(result["status"], 499)

    def test_online_import_disabled(self):
        result = self.call("/api/games/chesscom/example", method="GET")
        self.assertEqual(result["status"], 404)


if __name__ == "__main__":
    unittest.main()
