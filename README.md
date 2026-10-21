# NextCraft Forge PRoot

Varian **tanpa root** dari [NextCraft Forge](https://github.com/Bymeopen/nextcraft-forge). Satu mesin runtime: `proot` statis
(`libproot.so` + `libproot_loader.so` di APK) menjalankan Ubuntu 24.04 ARM64 sebagai UID aplikasi, tanpa `su`, tanpa mount kernel.

| | nextcraft-forge (chroot) | nextcraft-forge-proot (repo ini) |
|---|---|---|
| applicationId | `com.nextcraft.forge` | `com.nextcraft.forge.proot` |
| Syarat | Magisk / KernelSU / APatch | tidak ada |
| Masuk rootfs | `su -mm` + mount + `chroot-exec.sh` | `/system/bin/sh proot-exec.sh` + `libproot.so` |
| Kecepatan build | native | 2–4x lebih lambat (ptrace) |
| Instal APK hasil build | senyap (`pm install`) | dialog `PackageInstaller` |
| Logcat aplikasi hasil build | via `su` | butuh `adb shell pm grant com.nextcraft.forge.proot android.permission.READ_LOGS` |
| Phantom-process killer | dimatikan otomatis | manual via ADB (lihat `docs/NON_ROOT.md`) |
| Claude Code | biner resmi (Bun) | npm `2.1.112` (rilis Bun mati `SIGSYS`/`rseq` di seccomp aplikasi) |

Bundle runtime (Core, Python, Claude, DSH, Agy, Android) **sama persis** dengan repo chroot dan diunduh dari rilis yang sama;
hanya cara peluncurannya yang berbeda. Kedua aplikasi bisa dipasang berdampingan.

## Build

```bash
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

CI (`.github/workflows/ci.yml`) juga menolak setiap jalur `su`/`su -mm` yang masuk kembali ke `app/src/main`.

## Dokumen

- `docs/NON_ROOT.md`: batasan mode PRoot dan cara kerjanya.
- `docs/RUNTIME_KNOWN_ISSUES.md`: asal biner `proot`, SHA-256, masalah yang diketahui.
- `scripts/runtime-bundles/README.md`: skrip supervisor dan pembangun bundle (reproducible, checksum dipin).
- `ARCHITECTURE.md`: ADR warisan dari repo chroot; ADR-008 di bagian atas menjelaskan fork ini.

## Lisensi

GPL-3.0-only, lihat `LICENSE` dan `THIRD_PARTY_NOTICES.md` (library terminal Termux bersifat GPLv3, sehingga
distribusi APK wajib menyertakan source code). Copyright (C) 2026 Bymeopen.
