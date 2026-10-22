#!/usr/bin/env bash
set -euo pipefail

# Build the NextCraft Forge Claude Code bundle from the official Linux ARM64 release, without a phone.
#
# The binary is downloaded from Anthropic's release bucket and checked twice: against the checksum published in
# that release's manifest.json, and against the SHA-256 pinned below (so a changed upstream manifest cannot slip a
# different binary in). The binary does not depend on the Ubuntu release of the rootfs.
VERSION="${NEXTCRAFT_CLAUDE_VERSION:-2.1.263}"
SOURCE_SHA256="${NEXTCRAFT_CLAUDE_SHA256:-7d25d7c8ae6c6e009cc7dae4e817f674179fd31fb7761bcd56fee4c2902b4c03}"
BUNDLE_VERSION="${NEXTCRAFT_CLAUDE_BUNDLE_VERSION:-2026.10.1}"
RELEASE_BASE_URL="${NEXTCRAFT_CLAUDE_RELEASE_BASE_URL:-https://downloads.claude.ai/claude-code-releases}"
ARCHIVE="nextcraft-claude-arm64-${BUNDLE_VERSION}.tar.zst"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="${NEXTCRAFT_OUT_DIR:-$ROOT_DIR/dist/runtime-bundles}"

for tool in curl zstd python3; do
  command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 1; }
done
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Invalid Claude Code version: $VERSION" >&2; exit 1; }

sha256_of() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT
mkdir -p "$temp_dir/payload/usr/local/bin" "$OUT_DIR"

curl -fsSL --retry 3 -o "$temp_dir/manifest.json" "$RELEASE_BASE_URL/$VERSION/manifest.json"
manifest_sha256="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["platforms"]["linux-arm64"]["checksum"])' "$temp_dir/manifest.json")"
[[ "$manifest_sha256" == "$SOURCE_SHA256" ]] || {
  echo "Release manifest checksum ($manifest_sha256) does not match the pinned SHA-256" >&2
  exit 1
}

curl -fL --retry 3 -o "$temp_dir/claude" "$RELEASE_BASE_URL/$VERSION/linux-arm64/claude"
[[ "$(sha256_of "$temp_dir/claude")" == "$SOURCE_SHA256" ]] || {
  echo "Claude Code binary checksum mismatch" >&2
  exit 1
}

install -m 0755 "$temp_dir/claude" "$temp_dir/payload/usr/local/bin/claude"
printf '%s\n' "$VERSION" > "$temp_dir/payload/.nextcraft-claude-version"
printf '%s\n' "$VERSION" > "$temp_dir/payload/.nextcraft-claude-version"
cp "$temp_dir/payload/.nextcraft-claude-version" "$temp_dir/payload/.pocket-claude-version"  # older app builds read the legacy name

python3 "$SCRIPT_DIR/create_deterministic_tar.py" "$temp_dir/payload" "$temp_dir/payload.tar"
zstd -19 -T0 -f "$temp_dir/payload.tar" -o "$OUT_DIR/$ARCHIVE"
sha256_of "$OUT_DIR/$ARCHIVE"
wc -c "$OUT_DIR/$ARCHIVE" "$temp_dir/payload.tar"
echo "Claude Code bundle created: $OUT_DIR/$ARCHIVE"
