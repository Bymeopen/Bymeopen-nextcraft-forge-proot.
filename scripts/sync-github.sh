#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

COMMIT_MSG="${1:-Sync project changes to GitHub}"

# 1. Keep BLOK_1.md .. BLOK_7.md, BLOK_1_SAMPAI_4_COMPLETE.md, and BLOK_1_SAMPAI_7_COMPLETE.md synchronized with source files
python3 - "$ROOT_DIR" <<'PY'
import pathlib, re, sys

root = pathlib.Path(sys.argv[1])
blok_files_1_4 = ["BLOK_1.md", "BLOK_2.md", "BLOK_3.md", "BLOK_4.md"]
blok_files_all = ["BLOK_1.md", "BLOK_2.md", "BLOK_3.md", "BLOK_4.md", "BLOK_5.md", "BLOK_6.md", "BLOK_7.md"]

section_re = re.compile(
    r"(## \d+\. `([^`]+)` \()\d+( baris\)\n\n```([a-zA-Z0-9_-]*)\n)(.*?)(\n```)",
    re.DOTALL
)
bullet_re = re.compile(r"(- `([^`]+)` \()\d+( baris\))")

for bf in blok_files_all:
    bp = root / bf
    if not bp.is_file():
        continue
    text = bp.read_text(encoding="utf-8")

    def repl_bullet(m):
        prefix, rel_path, suffix = m.groups()
        src = root / rel_path
        if src.is_file():
            new_code = src.read_text(encoding="utf-8").rstrip("\n")
            line_count = len(new_code.splitlines())
            return f"{prefix}{line_count}{suffix}"
        return m.group(0)

    def repl_section(m):
        prefix, rel_path, mid, _lang, _old_code, suffix = m.groups()
        src = root / rel_path
        if src.is_file():
            new_code = src.read_text(encoding="utf-8").rstrip("\n")
            line_count = len(new_code.splitlines())
            return f"{prefix}{line_count}{mid}{new_code}{suffix}"
        return m.group(0)

    new_text = bullet_re.sub(repl_bullet, text)
    new_text = section_re.sub(repl_section, new_text)
    if new_text != text:
        bp.write_text(new_text, encoding="utf-8")

combined_1_4_parts = [
    "# DOKUMENTASI LENGKAP NEXTCRAFT FORGE — BLOK 1 SAMPAI BLOK 4 (TERINTEGRASI & TERSINKRONISASI)\n\n"
    "> Dokumen ini menggabungkan secara utuh seluruh kode sumber inti dan arsitektur dari:\n"
    "> - `BLOK_1.md` (Core Models, Native PTY Spawn C/JNI, Rootfs Repair, Chroot Scripts, RuntimeInstaller)\n"
    "> - `BLOK_2.md` (WorkspaceCheckpoints, RuntimeBridge, LocalFormatGateway, OpenRouterRoutingGateway, ApiKeyVault, AppPreferences)\n"
    "> - `BLOK_3.md` (AgentDriver, ClaudeRuntimeBridge, AntigravityRuntimeBridge, AntigravityAuthController, DshRuntimeBridge)\n"
    "> - `BLOK_4.md` (Android Build & Install Pipeline, Foreground Services, Network Gateways, GitHub OAuth, AppUpdater)\n"
]
for bf in blok_files_1_4:
    bp = root / bf
    if bp.is_file():
        combined_1_4_parts.append(bp.read_text(encoding="utf-8").strip())

combined_1_4_path = root / "BLOK_1_SAMPAI_4_COMPLETE.md"
new_combined_1_4 = "\n\n---\n\n".join(combined_1_4_parts) + "\n"
if not combined_1_4_path.is_file() or combined_1_4_path.read_text(encoding="utf-8") != new_combined_1_4:
    combined_1_4_path.write_text(new_combined_1_4, encoding="utf-8")

combined_1_7_parts = [
    "# DOKUMENTASI LENGKAP NEXTCRAFT FORGE — BLOK 1 SAMPAI BLOK 7 (TERINTEGRASI & TERSINKRONISASI PENUH)\n\n"
    "> Dokumen ini menggabungkan secara utuh seluruh kode sumber aplikasi Android **NextCraft Forge (`com.nextcraft.forge`)** dari Blok 1 hingga Blok 7:\n"
    "> - `BLOK_1.md` (Core Models, Native PTY Spawn C/JNI, Rootfs Repair, Chroot Scripts, RuntimeInstaller)\n"
    "> - `BLOK_2.md` (WorkspaceCheckpoints, RuntimeBridge, LocalFormatGateway, OpenRouterRoutingGateway, ApiKeyVault, AppPreferences)\n"
    "> - `BLOK_3.md` (AgentDriver, ClaudeRuntimeBridge, AntigravityRuntimeBridge, AntigravityAuthController, DshRuntimeBridge)\n"
    "> - `BLOK_4.md` (Android Build & Install Pipeline, Foreground Services, Network Gateways, GitHub OAuth, AppUpdater)\n"
    "> - `BLOK_5.md` (MainActivity, MainViewModel State Orchestrator, Theme, Localization, ModelLabels, AndroidBuildText, MarkdownText)\n"
    "> - `BLOK_6.md` (Jetpack Compose IDE Screens: NextCraftForgeApp, AgentScreen, FilesAndDiffScreen, TerminalScreen, AndroidAndPreviewScreen, SettingsScreenModern)\n"
    "> - `BLOK_7.md` (Gradle Build Scripts, AndroidManifest, Multi-Language Resources, FileProvider & Network Security, Runtime Bundle Docs)\n"
]
for bf in blok_files_all:
    bp = root / bf
    if bp.is_file():
        combined_1_7_parts.append(bp.read_text(encoding="utf-8").strip())

combined_1_7_path = root / "BLOK_1_SAMPAI_7_COMPLETE.md"
new_combined_1_7 = "\n\n---\n\n".join(combined_1_7_parts) + "\n"
if not combined_1_7_path.is_file() or combined_1_7_path.read_text(encoding="utf-8") != new_combined_1_7:
    combined_1_7_path.write_text(new_combined_1_7, encoding="utf-8")
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
