"""Shared location for disposable audit output, separate from source and fixtures."""
from pathlib import Path

ARTIFACT_DIR = Path(__file__).resolve().parents[1] / "artifacts"
ARTIFACT_DIR.mkdir(exist_ok=True)
