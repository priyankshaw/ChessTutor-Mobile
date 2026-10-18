"""Direct in-process adapter. No HTTP listener, server, or network downloads."""
import asyncio
import io
import json
import os
import threading
from types import SimpleNamespace
from urllib.parse import urlsplit, parse_qs

from mobile_errors import HTTPException, Cancelled

_cancelled = set()
_active = None
_state_lock = threading.Lock()
_evaluator = None
_opponent = None
_analyzer = None
_endpoints = None


def checkpoint():
    with _state_lock:
        if _active in _cancelled:
            raise Cancelled("Analysis cancelled.")


def cancel(request_id):
    with _state_lock:
        _cancelled.add(request_id)
        # Abandoned requests are bounded, including cancellations arriving late.
        if len(_cancelled) > 256:
            _cancelled.intersection_update({_active, request_id})


def initialize(engine_path):
    global _evaluator, _opponent, _analyzer, _endpoints
    if _evaluator is not None:
        return
    if not os.path.isfile(engine_path):
        raise RuntimeError("Bundled Stockfish is missing.")
    from backend.engine import stockfish_manager
    stockfish_manager.ensure_stockfish = lambda: engine_path
    import chess.engine
    from backend.engine.evaluator import StockfishEvaluator
    from backend.engine.opponent import StockfishOpponent
    from backend.services import analyzer
    from backend import mobile_endpoints

    class MobileEvaluator(StockfishEvaluator):
        def _start_engine(self):
            if not self.engine:
                engine = chess.engine.SimpleEngine.popen_uci(self.path, timeout=45)
                try:
                    engine.configure({"Threads": 2, "Hash": 64, "UCI_ShowWDL": True})
                except Exception:
                    engine.quit()
                    raise
                self.engine = engine

        def _heuristic_fallback(self, board):
            raise RuntimeError("Stockfish analysis failed. Restart the app and retry.")

        def evaluate_position(self, *args, **kwargs):
            checkpoint()
            result = super().evaluate_position(*args, **kwargs)
            checkpoint()
            return result

    class MobileOpponent(StockfishOpponent):
        def _start(self):
            if self.engine is None:
                engine = chess.engine.SimpleEngine.popen_uci(engine_path, timeout=45)
                try:
                    engine.configure({"Threads": 2, "Hash": 32})
                except Exception:
                    engine.quit()
                    raise
                self.engine = engine

    original_explanation = analyzer.generate_heuristic_explanation
    if not getattr(original_explanation, "_mobile_wrapped", False):
        def explanation_with_checkpoint(*args, **kwargs):
            checkpoint()
            return original_explanation(*args, **kwargs)
        explanation_with_checkpoint._mobile_wrapped = True
        analyzer.generate_heuristic_explanation = explanation_with_checkpoint

    _evaluator = MobileEvaluator(stockfish_path=engine_path)
    _opponent = MobileOpponent()
    _analyzer = analyzer.GameAnalyzer(_evaluator)
    _endpoints = mobile_endpoints
    _endpoints.evaluator = _evaluator


def _text(body, key, limit=1000000):
    value = body.get(key)
    if not isinstance(value, str) or not value.strip() or len(value) > limit:
        raise ValueError(f"Invalid {key}.")
    return value


def _depth(body, default=14):
    depth = body.get("depth", default)
    if type(depth) is not int or not 10 <= depth <= 22:
        raise ValueError("Analysis depth must be between 10 and 22.")
    return depth


def dispatch(payload, progress=None):
    import chess
    import chess.pgn
    from backend.engine.opponent import replay_game, LEVELS
    url = urlsplit(payload.get("path", ""))
    path = url.path
    method = payload.get("method", "GET").upper()
    body = payload.get("body") or {}
    if not isinstance(body, dict):
        raise ValueError("Invalid request body.")
    query = {key: values[0] for key, values in parse_qs(url.query).items()}
    if method == "GET" and path == "/api/status":
        return {"status": "offline", "engine": "Stockfish 19 NNUE · on device",
                "stockfish_ready": _evaluator.engine is not None}
    if method == "POST" and path == "/api/parse-pgn":
        pgn = _text(body, "pgn")
        game = chess.pgn.read_game(io.StringIO(pgn))
        if game is None or game.errors:
            raise ValueError("Invalid PGN. Check the notation and try again.")
        moves = [move.uci() for move in game.mainline_moves()]
        if (not moves and not body.get("allow_empty")) or len(moves) > 2000:
            raise ValueError("PGN must contain between 1 and 2000 legal moves.")
        if game.headers.get("Variant", "Standard").lower() not in ("standard", "chess"):
            raise ValueError("Only standard chess games are supported.")
        return {"headers": dict(game.headers), "start_fen": game.board().fen(), "moves": moves}
    if method == "POST" and path == "/api/validate-fen":
        board = chess.Board(_text(body, "fen", 120))
        if not board.is_valid():
            raise ValueError("Invalid chess position.")
        return {"fen": board.fen()}
    if method == "POST" and path == "/api/analyze":
        pgn = _text(body, "pgn")
        game = chess.pgn.read_game(io.StringIO(pgn))
        if game is None or game.errors or not list(game.mainline_moves()):
            raise ValueError("Invalid PGN or no legal moves to review.")
        if sum(1 for _ in game.mainline_moves()) > 2000:
            raise ValueError("PGN exceeds 2000 moves.")
        return asyncio.run(_analyzer.analyze_game(pgn, depth=_depth(body, 16), use_ai=False, on_progress=progress))
    if method == "POST" and path == "/api/evaluate-move":
        rating = body.get("player_elo")
        if rating is not None and (type(rating) is not int or not 0 < rating <= 4000):
            raise ValueError("Invalid player rating.")
        history = body.get("history_san")
        if history is not None and (not isinstance(history, list) or len(history) > 2000 or any(not isinstance(san, str) or len(san) > 20 for san in history)):
            raise ValueError("Invalid SAN history.")
        start_fen = body.get("start_fen")
        if start_fen is not None and (not isinstance(start_fen, str) or len(start_fen) > 120 or not chess.Board(start_fen).is_valid()):
            raise ValueError("Invalid starting position.")
        previous_error = body.get("prev_was_opponent_error", False)
        if not isinstance(previous_error, bool):
            raise ValueError("Invalid previous move context.")
        req = SimpleNamespace(fen_before=_text(body, "fen_before", 120),
                              move_uci=_text(body, "move_uci", 5),
                              history_san=history, depth=_depth(body), player_elo=rating,
                              start_fen=start_fen, prev_was_opponent_error=previous_error)
        return _endpoints.evaluate_move_endpoint(req)
    if method == "POST" and path == "/api/check-solution":
        req = SimpleNamespace(fen=_text(body, "fen", 120), move_uci=_text(body, "move_uci", 5),
                              best_move_uci=_text(body, "best_move_uci", 5))
        return _endpoints.check_solution_endpoint(req)
    if method == "POST" and path == "/api/play/move":
        level = body.get("level", "1800")
        if level not in LEVELS:
            raise ValueError("Unsupported opponent level.")
        moves = body.get("moves", [])
        if not isinstance(moves, list) or len(moves) > 2000:
            raise ValueError("Invalid move history.")
        board = replay_game(body.get("start_fen", chess.STARTING_FEN), moves)
        return _opponent.choose_move(board, level, max(0.1, min(30, float(body.get("think_time", 1)))),
                                     _text(body, "game_id", 100))
    if method == "POST" and path == "/api/openings/explore":
        return _endpoints.explore_opening_endpoint(SimpleNamespace(
            fen=body.get("fen"), history_san=body.get("history_san", [])))
    if method == "GET" and path == "/api/openings/popular":
        return _endpoints.get_popular_openings_endpoint(
            limit=max(1, min(100, int(query.get("limit", 40)))), side=query.get("side", "both"))
    if method == "GET" and path == "/api/openings/search":
        return _endpoints.search_openings_endpoint(
            q=query.get("q", ""), category=query.get("category", "all"),
            limit=max(1, min(100, int(query.get("limit", 50)))), side=query.get("side", "both"))
    raise HTTPException(404, "Feature unavailable in offline edition. Import a PGN file instead.")


def request(request_id, request_json, reporter=None):
    global _active
    _active = request_id
    try:
        checkpoint()
        payload = json.loads(request_json)
        if _evaluator is None and payload.get("path") not in ("/api/parse-pgn", "/api/validate-fen"):
            raise RuntimeError("Chess runtime has not started.")
        async def progress(value):
            checkpoint()
            if reporter is not None:
                reporter.update(json.dumps(value))
        result = dispatch(payload, progress if reporter is not None else None)
        checkpoint()
        return json.dumps({"status": 200, "body": result})
    except Cancelled as exc:
        return json.dumps({"status": 499, "body": {"detail": str(exc)}})
    except HTTPException as exc:
        return json.dumps({"status": exc.status_code, "body": {"detail": exc.detail}})
    except (ValueError, TypeError, KeyError) as exc:
        return json.dumps({"status": 400, "body": {"detail": str(exc)}})
    except Exception as exc:
        return json.dumps({"status": 503, "body": {"detail": str(exc)}})
    finally:
        with _state_lock:
            _cancelled.discard(request_id)
            _active = None


def close():
    global _evaluator, _opponent, _analyzer
    if _evaluator is not None:
        _evaluator.close()
    if _opponent is not None:
        _opponent.close()
    _evaluator = _opponent = _analyzer = None
