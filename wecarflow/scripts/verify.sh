#!/usr/bin/env zsh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
python3 -m unittest discover -s "$PROJECT_DIR/tests" -v
python3 -m unittest discover -s "$SCRIPT_DIR/tests" -v
python3 "$SCRIPT_DIR/apply_patches.py" --compile-only
