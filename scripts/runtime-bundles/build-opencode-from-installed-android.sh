#!/usr/bin/env bash
set -euo pipefail

# Package the pinned OpenCode CLI installation from PocketDev's private Ubuntu
# runtime. Only the npm payload, launcher symlink, and version marker are
# exported; provider settings, auth.json, API keys, sessions, chats, and
# workspaces are not.
#
# Why a bundle at all: OpenCode Zen's free models are refused to arbitrary HTTP
# clients with `403 "OpenCode's free tier can only be used from within OpenCode"`.
# Only the genuine `opencode` binary is accepted, so the app must ship and run
# the real client to reach the free catalog keylessly. This script produces that
# client as a checksum-pinned overlay, exactly like the DeepSeek Harness and
# Antigravity bundles.
ADB_SERIAL="${ADB_SERIAL:-}"
PACKAGE="${POCKETDEV_PACKAGE:-com.jarves.mh}"
VERSION="${POCKETDEV_OPENCODE_BUNDLE_VERSION:-2026.09.1}"
OPENCODE_VERSION="${POCKETDEV_OPENCODE_VERSION:-1.18.33}"
ARCHIVE="pocketdev-opencode-arm64-${VERSION}.tar.zst"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

adb_cmd() {
  if [[ -n "$ADB_SERIAL" ]]; then adb -s "$ADB_SERIAL" "$@"; else adb "$@"; fi
}

command -v zstd >/dev/null || { echo "zstd is required" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 1; }
adb_cmd wait-for-device

# The installed runtime must already contain the pinned OpenCode CLI. Verify the
# launcher exists and the marker matches so a mismatched guest fails fast rather
# than shipping a bundle the app would reject on --version.
adb_cmd shell run-as "$PACKAGE" test -x files/runtime/ubuntu/usr/local/bin/opencode
actual_version="$(adb_cmd exec-out run-as "$PACKAGE" cat files/runtime/ubuntu/.pocket-opencode-version | tr -d '\r\n')"
[[ "$actual_version" == "$OPENCODE_VERSION" ]] || {
  echo "Expected OpenCode $OPENCODE_VERSION, found $actual_version" >&2
  exit 1
}
# The real ARM64 binary (not just the npm wrapper) must be present, or the
# genuine-client identity that unlocks the free tier is lost.
adb_cmd shell run-as "$PACKAGE" test -f files/runtime/ubuntu/usr/local/lib/opencode/node_modules/opencode-linux-arm64/bin/opencode

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"; adb_cmd shell run-as "$PACKAGE" rm -f cache/pocketdev-opencode-export.tar >/dev/null 2>&1 || true' EXIT
mkdir -p "$temp_dir/payload" dist/runtime-bundles

# Export only the npm payload, the launcher symlink, and the version marker.
# Deliberately excludes any auth.json / config so no credentials can leak.
adb_cmd shell run-as "$PACKAGE" tar -cf cache/pocketdev-opencode-export.tar \
  -C files/runtime/ubuntu usr/local/lib/opencode usr/local/bin/opencode .pocket-opencode-version
adb_cmd exec-out run-as "$PACKAGE" cat cache/pocketdev-opencode-export.tar > "$temp_dir/source.tar"
tar -xf "$temp_dir/source.tar" -C "$temp_dir/payload"

[[ "$(cat "$temp_dir/payload/.pocket-opencode-version")" == "$OPENCODE_VERSION" ]]
[[ -L "$temp_dir/payload/usr/local/bin/opencode" ]]
[[ -f "$temp_dir/payload/usr/local/lib/opencode/node_modules/opencode-linux-arm64/bin/opencode" ]]
# Belt-and-braces: refuse to ship credentials if the guest somehow had them.
if [[ -e "$temp_dir/payload/usr/local/lib/opencode/auth.json" ]]; then
  echo "Refusing to build: auth.json present in payload" >&2
  exit 1
fi

python3 "$SCRIPT_DIR/create_deterministic_tar.py" "$temp_dir/payload" "$temp_dir/payload.tar"
zstd -19 -T0 -f "$temp_dir/payload.tar" -o "dist/runtime-bundles/$ARCHIVE"

shasum -a 256 "dist/runtime-bundles/$ARCHIVE"
wc -c "dist/runtime-bundles/$ARCHIVE" "$temp_dir/payload.tar"
cat <<EOF
OpenCode bundle created: dist/runtime-bundles/$ARCHIVE

Next: paste the sha256 (first line above) and the compressed byte count into
RuntimeInstaller.OPENCODE_BUNDLE (currently a placeholder that blocks install).
EOF
