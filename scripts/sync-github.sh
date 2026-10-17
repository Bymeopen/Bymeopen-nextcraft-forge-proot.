#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

COMMIT_MSG="${1:-Sync project changes to GitHub}"

# 1. Keep BLOK_1.md .. BLOK_4.md and BLOK_1_SAMPAI_4_COMPLETE.md synchronized with source files
python3 - <<'PY'
import pathlib, re

root = pathlib.Path("/workspace/mobilebuilder")
blok_files = ["BLOK_1.md", "BLOK_2.md", "BLOK_3.md", "BLOK_4.md"]
header_re = re.compile(
    r"(### \d+\.\d+ `([^`]+)`\n\n```([a-zA-Z0-9_-]*)\n)(.*?)(\n```)",
    re.DOTALL
)

for bf in blok_files:
    bp = root / bf
    if not bp.is_file():
        continue
    text = bp.read_text(encoding="utf-8")
    def repl(m):
        prefix, rel_path, _lang, old_code, suffix = m.groups()
        src = root / rel_path
        if src.is_file():
            new_code = src.read_text(encoding="utf-8").rstrip("\n")
            return f"{prefix}{new_code}{suffix}"
        return m.group(0)
    new_text = header_re.sub(repl, text)
    if new_text != text:
        bp.write_text(new_text, encoding="utf-8")

combined_parts = [
    "# DOKUMENTASI LENGKAP NEXTCRAFT FORGE — BLOK 1 SAMPAI BLOK 4 (TERINTEGRASI & TERSINKRONISASI)\n\n"
    "> Dokumen ini menggabungkan secara utuh seluruh kode sumber inti dan arsitektur dari:\n"
    "> - `BLOK_1.md` (Core Models, Native PTY Spawn C/JNI, Rootfs Repair, Chroot Scripts, RuntimeInstaller)\n"
    "> - `BLOK_2.md` (WorkspaceCheckpoints, RuntimeBridge, LocalFormatGateway, OpenRouterRoutingGateway, ApiKeyVault, AppPreferences)\n"
    "> - `BLOK_3.md` (AgentDriver, ClaudeRuntimeBridge, AntigravityRuntimeBridge, AntigravityAuthController, DshRuntimeBridge)\n"
    "> - `BLOK_4.md` (Android Build & Install Pipeline, Foreground Services, Network Gateways, GitHub OAuth, AppUpdater)\n"
]
for bf in blok_files:
    bp = root / bf
    if bp.is_file():
        combined_parts.append(bp.read_text(encoding="utf-8").strip())

combined_path = root / "BLOK_1_SAMPAI_4_COMPLETE.md"
new_combined = "\n\n---\n\n".join(combined_parts) + "\n"
if not combined_path.is_file() or combined_path.read_text(encoding="utf-8") != new_combined:
    combined_path.write_text(new_combined, encoding="utf-8")
PY

# 2. Stage and commit any local modifications
git add -A
if ! git diff --cached --quiet; then
    git commit -m "$COMMIT_MSG"
fi

# 3. Sync with GitHub remote origin/main
if git remote get-url origin >/dev/null 2>&1; then
    git pull --rebase --autostash origin main
    git push -u origin main
fi
