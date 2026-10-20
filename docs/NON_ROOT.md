# NextCraft Forge PRoot: cara kerja tanpa root

Aplikasi ini hanya punya satu mesin: `proot` statis dari APK. Tidak ada `su`, tidak ada mount kernel, tidak ada `chown`.

| | nextcraft-forge (chroot) | nextcraft-forge-proot |
|---|---|---|
| Cara masuk | `su -mm` + mount + `chroot-exec.sh` | `/system/bin/sh proot-exec.sh` + `libproot.so` (ptrace) |
| Kecepatan | penuh | build Gradle/npm kira-kira 2–4x lebih lambat |
| Instal APK hasil build | senyap | dialog `PackageInstaller` biasa |
| Logcat aplikasi hasil build | `su` | `adb shell pm grant com.nextcraft.forge.proot android.permission.READ_LOGS` sekali |
| Phantom-process killer | dimatikan otomatis | manual, lihat di bawah |
| Claude Code | biner resmi | npm `@anthropic-ai/claude-code@2.1.112` (rilis Bun mati `SIGSYS`, exit 159) |

## Catatan

- Layar onboarding menampilkan status mesin PRoot; Diagnostik menampilkan `Runtime engine: proot`.
- `chroot-health.sh` melewati bagian *Mount* (tidak ada mount kernel) dan mencetak `engine proot`.
- Android 12+ membunuh proses latar yang banyak (*phantom process killer*). Matikan lewat ADB sekali:
  `adb shell device_config put activity_manager max_phantom_processes 2147483647` dan
  `adb shell settings put global settings_enable_monitor_phantom_procs false`.
- Bila `proot` gagal dengan pesan seccomp/ptrace, set `PROOT_NO_SECCOMP=1` di environment agen (Pengaturan → Agent).
- `apt-get` berjalan sebagai root palsu (`-0`); paket yang memakai hard link ditangani `--link2symlink`.
- Claude Code dipasang dan diperbarui hanya dari npm `2.1.112`; cek pembaruan tidak pernah menawarkan rilis Bun yang lebih baru.
- Semua berkas rootfs milik UID aplikasi, jadi *Disconnect GitHub*, *Sign out*, dan perbaikan rootfs tidak butuh eskalasi.

## Asal biner

`app/src/main/jniLibs/arm64-v8a/libproot.so` (+ `libproot_loader.so`, loader ELF yang dirujuk `PROOT_LOADER`) = `proot` v5.5.0
(`proot-me/proot`, commit `25dc6a31`) + `talloc` 2.4.3, dibangun statis (glibc) oleh `scripts/runtime-bundles/build-proot-static.sh`
di chroot Core 24.04 ARM64 (di mesin pengembang, bukan di ponsel). SHA-256 tercatat di `docs/RUNTIME_KNOWN_ISSUES.md`. Android hanya
mengizinkan eksekusi dari `nativeLibraryDir`, karena itu biner dikemas sebagai native library (`jniLibs.useLegacyPackaging = true`).
