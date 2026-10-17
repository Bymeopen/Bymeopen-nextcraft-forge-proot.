---
trigger: always_on
description: Always keep local project files, BLOK_1_SAMPAI_7_COMPLETE.md documentation, and GitHub (origin/main) in sync after every project change.
---

# Mandatory Local & GitHub Synchronization Rule

Whenever any source file, script, configuration, or documentation in `/workspace/mobilebuilder` is created, modified, or deleted:

1. Run `bash scripts/verify-core-fixes.sh` to verify that all core architectural, security, and full 41-file Kotlin + Compose IR compilation checks pass.
2. Run `bash scripts/sync-github.sh "<concise commit message>"` so that:
   - `BLOK_1_SAMPAI_7_COMPLETE.md` stays 100% synchronized with the local source files.
   - All changes are committed locally on branch `main` (`@Bymeopen <byme.openwrt@gmail.com>`).
   - All commits are immediately synchronized (`git pull --rebase --autostash origin main && git push -u origin main`) with `https://github.com/Bymeopen/nextcraft-forge.git`.
