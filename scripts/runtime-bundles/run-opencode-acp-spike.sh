#!/usr/bin/env bash
set -uo pipefail

# Run the OpenCode ACP spike inside the PocketDev Ubuntu guest (app terminal).
#
# Installs the pinned OpenCode CLI if it is not already present, then drives
# `opencode acp` through the exact handshake the app uses and prints a summary
# of every session/update kind, tool call, and permission request observed.
#
# Usage (inside the guest):
#   bash /path/to/run-opencode-acp-spike.sh
#   PROMPT="list the files in /workspace" MODEL=big-pickle bash .../run-opencode-acp-spike.sh
#
# The raw nd-JSON transcript is written to $WORKSPACE/acp-spike.ndjson. Paste
# the summary (and ideally the transcript) back into the app to finalize the
# bridge's event mapping.

WORKSPACE="${WORKSPACE:-/workspace}"
OPENCODE_VERSION="${OPENCODE_VERSION:-1.18.33}"
PREFIX="${PREFIX:-/usr/local}"
NPM_PREFIX="$PREFIX/lib/opencode"
SPIKE_JS="$(cd "$(dirname "$0")" && pwd)/opencode-acp-spike.js"
PROMPT="${PROMPT:-Reply with the single word: pong}"
MODEL="${MODEL:-}"
DURATION="${DURATION:-90}"

say() { printf '%s\n' "$*"; }

say "== OpenCode ACP spike =="
say "workspace : $WORKSPACE"
say "version   : $OPENCODE_VERSION"
say "prompt    : $PROMPT"
say ""

# The guest must already have the core runtime (node/npm). Fail fast otherwise.
command -v node >/dev/null || { say "!! node not found -- install the core runtime bundle first"; exit 1; }
command -v npm  >/dev/null || { say "!! npm not found -- install the core runtime bundle first"; exit 1; }
say "node      : $(node --version)"
say "npm       : $(npm --version)"

# Install the pinned CLI if missing or the wrong version. Prefer the already
# bundled install path; fall back to a local npm install.
current_version=""
if [ -x "$PREFIX/bin/opencode" ]; then
  current_version="$("$PREFIX/bin/opencode" --version 2>/dev/null | tr -d '\r\n' || true)"
fi

if [ "$current_version" != "$OPENCODE_VERSION" ]; then
  say ""
  say "-- installing opencode@$OPENCODE_VERSION (current: ${current_version:-none})"
  if [ -d "$NPM_PREFIX" ]; then
    ( cd "$NPM_PREFIX" && npm install --no-audit --no-fund --omit=dev "opencode-ai@$OPENCODE_VERSION" )
  else
    mkdir -p "$NPM_PREFIX"
    ( cd "$NPM_PREFIX" && npm init -y >/dev/null 2>&1 && npm install --no-audit --no-fund --omit=dev "opencode-ai@$OPENCODE_VERSION" "opencode-linux-arm64@$OPENCODE_VERSION" )
    mkdir -p "$PREFIX/bin"
    ln -sf "$NPM_PREFIX/node_modules/.bin/opencode" "$PREFIX/bin/opencode"
    printf '%s' "$OPENCODE_VERSION" > "$PREFIX/.pocket-opencode-version"
  fi
else
  say ""
  say "-- opencode $OPENCODE_VERSION already installed"
fi

[ -x "$PREFIX/bin/opencode" ] || { say "!! $PREFIX/bin/opencode missing after install"; exit 1; }

say ""
say "-- running: opencode acp"
say "   (transcript -> $WORKSPACE/acp-spike.ndjson)"
say ""

node "$SPIKE_JS" \
  --binary "$PREFIX/bin/opencode" \
  --cwd "$WORKSPACE" \
  --prompt "$PROMPT" \
  --seconds "$DURATION" \
  ${MODEL:+--model "$MODEL"}
rc=$?

say ""
if [ "$rc" -eq 0 ]; then
  say "spike finished. Review the summary above and share it with the app."
else
  say "spike exited $rc -- the transcript may still contain useful partial output:"
  say "  $WORKSPACE/acp-spike.ndjson"
fi
exit "$rc"
