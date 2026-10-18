"""Package reproducible native sources without local tools, keys or build caches."""
from hashlib import sha256
from pathlib import Path
import json
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = re.search(r'versionName\s*=\s*"([^"]+)"', (ROOT / "native-android/app/build.gradle.kts").read_text()).group(1)
OUTPUT = ROOT / "artifacts/native-release"
OUTPUT.mkdir(parents=True, exist_ok=True)
SOURCE = OUTPUT / "ChessTutor-native-source.zip"
EXCLUDED = {".gradle", ".kotlin", "build", "__pycache__", "jniLibs"}

paths = [ROOT / name for name in (
    ".gitignore", "AGENTS.md", "README.md", "LICENSE", "THIRD_PARTY_NOTICES.md",
    "requirements.txt", "upstream-manifest.json",
)]
for name in ("native-android", "scripts", "tests", "licenses", "assets/coach"):
    for path in (ROOT / name).rglob("*"):
        relative = path.relative_to(ROOT)
        if (path.is_file() and not EXCLUDED.intersection(relative.parts)
                and path.name != "local.properties"
                and path.suffix not in {".pyc", ".jks", ".keystore"}):
            paths.append(path)

with zipfile.ZipFile(SOURCE.with_suffix(".tmp"), "w", zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(set(paths)):
        archive.write(path, path.relative_to(ROOT).as_posix())
    archive.write(ROOT / "vendor/chesslib/chesslib-1.3.7-source.tar.gz",
                  "third-party/chesslib-1.3.7-source.tar.gz")
    for network in (ROOT / "vendor/stockfish-native/arm64-v8a/build").glob("nn-*.nnue"):
        archive.write(network, "third-party/stockfish/" + network.name)
SOURCE.with_suffix(".tmp").replace(SOURCE)

with zipfile.ZipFile(SOURCE) as archive:
    assert archive.testzip() is None
    names = set(archive.namelist())
    assert "native-android/app/src/main/python/backend/mobile_endpoints.py" in names
    assert "native-android/app/src/main/python/backend/data/openings.sqlite" in names
    assert any(name.startswith("third-party/stockfish/nn-") for name in names)
    assert not any(set(Path(name).parts).intersection(EXCLUDED) for name in names)

artifacts = ("ChessTutor-native-arm64.apk", SOURCE.name, "Stockfish-19-corresponding-source.tar.gz")
hashes = {name: sha256((OUTPUT / name).read_bytes()).hexdigest() for name in artifacts}
(OUTPUT / "SHA256SUMS.txt").write_text(
    "".join(f"{digest}  {name}\n" for name, digest in hashes.items()), encoding="utf-8")
(OUTPUT / "build-manifest.json").write_text(json.dumps({
    "application_id": "com.chesstutor.nativeapp", "version": VERSION,
    "min_android": 12, "abi": "arm64-v8a", "signing": "preview/debug",
    "upstream_snapshot": json.loads((ROOT / "upstream-manifest.json").read_text())["synced_at"],
    "sha256": hashes,
}, indent=2) + "\n", encoding="utf-8")
print(f"Native source archive verified: {SOURCE.stat().st_size / 1024 / 1024:.1f} MB.")
