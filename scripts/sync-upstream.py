"""Snapshot desktop algorithms into the native app, without copying its web UI."""
import argparse
import ast
import hashlib
import json
from pathlib import Path
import runpy
import shutil
import tempfile
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
PYTHON = ROOT / "native-android/app/src/main/python"


def sync(source):
    source = source.resolve()
    if not (source / "backend/main.py").is_file():
        raise ValueError("Desktop source missing. Pass --source <ChessTutor folder>.")
    paths = [source / "backend/main.py"]
    paths += [path for folder in ("engine", "services") for path in (source / "backend" / folder).glob("*.py") if path.name != "game_fetcher.py"]
    paths += [p for p in (source / "backend/data").iterdir() if p.suffix in (".json", ".bin")]
    paths += list((source / "frontend/public/pieces/neo").glob("*.png"))
    paths += [source / "frontend/public/coach/tutor.webp", source / "frontend/public/coach/README.md", source / "LICENSE"]
    paths += [p for p in (source / "backend/tests").rglob("*") if p.is_file() and p.suffix in (".py", ".pgn", ".json") and "__pycache__" not in p.parts and p.name != "test_opponent.py"]
    paths += [source / "tools" / name for name in ("__init__.py", "audit_supplied_games.py", "audit_learner_games.py", "paths.py")]
    snapshot = {path: path.read_bytes() for path in paths}
    hashes = {path.relative_to(source).as_posix(): hashlib.sha256(data).hexdigest() for path, data in snapshot.items()}
    manifest = {"source": str(source), "synced_at": datetime.now(timezone.utc).isoformat(), "files": hashes,
                "adaptations": ["Direct in-process Python calls", "Stockfish Threads=2, Hash=64/32 MB", "Disk-backed opening membership", "Independent Kotlin/Compose UI"]}
    PYTHON.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="desktop-sync-", dir=ROOT) as temporary:
        stage = Path(temporary)
        backend = stage / "backend"
        for folder in ("engine", "services"):
            (backend / folder).mkdir(parents=True)
            for path, data in snapshot.items():
                if path.parent == source / "backend" / folder:
                    ast.parse(data.decode("utf-8-sig"))
                    (backend / folder / path.name).write_bytes(data)
            (backend / folder / "__init__.py").touch()
        (backend / "__init__.py").touch()
        (backend / "data").mkdir()
        for path, data in snapshot.items():
            if path.parent != source / "backend/data":
                continue
            if path.name == "openings_compiled.json":
                compiled = stage / path.name
                compiled.write_bytes(data)
                index = runpy.run_path(str(ROOT / "scripts/index-openings.py"))["index_openings"]
                manifest["opening_counts"] = index(compiled, backend / "data/openings.sqlite")
            elif path.suffix == ".json":
                (backend / "data" / path.name).write_text(json.dumps(json.loads(data), ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
            else:
                (backend / "data" / path.name).write_bytes(data)
        book_path = backend / "engine/book.py"
        book = book_path.read_text(encoding="utf-8")
        if book.count("\n_init_book()\n") != 1:
            raise ValueError("Desktop book storage changed. Review mobile adapter before syncing.")
        book_path.write_text(book.replace("\n_init_book()\n", '\nfrom mobile_book import load_book\nOPENINGS_MAP, PREFIX_SET, FEN_TO_OPENING, BOOK_FENS = load_book(os.path.join(os.path.dirname(DATA_FILE), "openings.sqlite"))\n'), encoding="utf-8")
        names = {"parse_board_move", "evaluate_move_endpoint", "check_solution_endpoint", "explore_opening_endpoint", "get_popular_openings_endpoint", "search_openings_endpoint"}
        functions = []
        for node in ast.parse(snapshot[source / "backend/main.py"].decode("utf-8-sig")).body:
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name in names:
                if node.name == "evaluate_move_endpoint":
                    supported = {"fen_before", "move_uci", "start_fen", "history_san", "depth", "player_elo", "prev_was_opponent_error"}
                    actual = {attribute.attr for attribute in ast.walk(node) if isinstance(attribute, ast.Attribute) and isinstance(attribute.value, ast.Name) and attribute.value.id == "req"}
                    if not actual <= supported:
                        raise ValueError(f"Desktop request fields changed: {actual - supported}. Update the native adapter.")
                node.decorator_list = []
                functions.append(ast.unparse(node))
        if len(functions) != len(names):
            raise ValueError("Desktop endpoint schema changed. Update mobile adapter before syncing.")
        (backend / "mobile_endpoints.py").write_text(
            '# Generated from desktop function bodies. Do not edit.\nfrom __future__ import annotations\nimport chess\nfrom mobile_errors import HTTPException\nfrom backend.engine.evaluator import compare_played_move\n\n'
            + "\n\n".join(functions) + "\n", encoding="utf-8")
        for path, data in snapshot.items():
            if hashlib.sha256(path.read_bytes()).hexdigest() != hashlib.sha256(data).hexdigest():
                raise ValueError(f"Desktop changed during sync: {path.name}. Retry after edits finish.")
        destination = (PYTHON / "backend").resolve()
        if not destination.is_relative_to(ROOT.resolve()):
            raise ValueError("Refusing to replace files outside mobile workspace.")
        previous = PYTHON / "backend.previous"
        if previous.exists():
            raise ValueError("Previous sync backup exists; restore or remove it before retrying.")
        if destination.exists(): destination.rename(previous)
        try:
            shutil.copytree(backend, destination)
        except BaseException:
            if destination.exists(): shutil.rmtree(destination)
            if previous.exists(): previous.rename(destination)
            raise
        if previous.exists(): shutil.rmtree(previous)
        test_destination = ROOT / "tests/desktop-snapshot"
        if test_destination.exists(): shutil.rmtree(test_destination)
        for path, data in snapshot.items():
            if path.is_relative_to(source / "backend/tests"):
                destination = test_destination / path.relative_to(source / "backend/tests")
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(data)
            elif path.parent == source / "tools":
                destination = ROOT / "tests/desktop-support/tools" / path.name
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(data)
        resources = ROOT / "native-android/app/src/main/res/drawable-nodpi"
        resources.mkdir(parents=True, exist_ok=True)
        for path, data in snapshot.items():
            if path.parent == source / "frontend/public/pieces/neo": (resources / path.name).write_bytes(data)
        (resources / "coach.webp").write_bytes(snapshot[source / "frontend/public/coach/tutor.webp"])
        (ROOT / "assets/coach").mkdir(parents=True, exist_ok=True)
        (ROOT / "assets/coach/README.md").write_bytes(snapshot[source / "frontend/public/coach/README.md"])
        (ROOT / "licenses/ChessTutor-Apache-2.0.txt").write_bytes(snapshot[source / "LICENSE"])
        generated = [p for p in (PYTHON / "backend").rglob("*") if p.is_file() and p.suffix != ".pyc"]
        generated += [p for folder in ("desktop-snapshot", "desktop-support/tools") for p in (ROOT / "tests" / folder).rglob("*") if p.is_file() and p.suffix in (".py", ".pgn", ".json")]
        manifest["bundled_files"] = {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(generated)}
        (ROOT / "upstream-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Synced {len(hashes)} desktop files into Kotlin app. Desktop untouched.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=ROOT.parent / "ChessTutor")
    sync(parser.parse_args().source)
