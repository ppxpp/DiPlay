#!/usr/bin/env zsh
# Default: build + QQMusic-compatible signing + verification. Installation is explicit.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec python3 "$SCRIPT_DIR/repack.py" "$@"
