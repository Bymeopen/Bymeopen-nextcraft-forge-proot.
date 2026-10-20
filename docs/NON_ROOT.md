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
- `apt-get` berjalan sebagai root palsu (`-0`) tanpa sandbox seccomp apt; paket yang memakai hard link ditangani `--link2symlink`.
- Seccomp aplikasi Android membunuh proses yang memanggil `io_uring_setup` (SIGSYS, exit 159). libuv di Node 20+ memakainya
  secara default, jadi `UV_USE_IO_URING=0` dan `GLIBC_TUNABLES=glibc.pthread.rseq=0` selalu diekspor ke guest.
- Claude Code dipasang dan diperbarui hanya dari npm `2.1.112`; cek pembaruan tidak pernah menawarkan rilis Bun yang lebih baru.
- Hook izin didaftarkan sebagai `PreToolUse` (dengan `permissionDecision`) selain `PermissionRequest`: di 2.1.112 mode headless
  (`-p`) tidak memanggil `PermissionRequest`, sehingga setiap perintah berakhir "This command requires approval".
- `/sdcard` di guest butuh izin Android "Akses semua file" (Pengaturan → Akses penyimpanan untuk /sdcard); tanpa itu `/storage`
  tidak di-bind.
- Semua berkas rootfs milik UID aplikasi, jadi *Disconnect GitHub*, *Sign out*, dan perbaikan rootfs tidak butuh eskalasi.

## Asal biner

`app/src/main/jniLibs/arm64-v8a/libproot.so` (+ `libproot_loader.so`, loader ELF yang dirujuk `PROOT_LOADER`) =
**`termux/proot`** tag `v5.1.107.98` (commit `e2d843ee6dab076ea0ed1ffd5d0ea12232185499`) + `talloc` 2.5.0 (statis),
dibangun dengan **Android NDK r27 (clang, bionic, API 24)** oleh `scripts/runtime-bundles/build-proot-ndk.sh`, persis resep
termux-packages. Hasilnya executable Android biasa: PIE, interpreter `/system/bin/linker64`, hanya butuh `libc.so`, segmen
16 KB. SHA-256 `libproot.so` `f6d4a32166dd41199e9da1f812aa5950ecd33de082bb65e9f114b79b8569a83f` (301.568 byte),
`libproot_loader.so` `46a4eae9941b77ae6216a5f01a1faf8b44e83a2ca1ed37be9501bcbc3cbc3f78` (18.296 byte).

Dua keputusan di baliknya:

1. **Fork Termux, bukan `proot-me/proot`.** Filter seccomp aplikasi Android menjawab setiap syscall di luar daftar izin bionic
   dengan SIGSYS (exit 159), bukan `ENOSYS`. Guest glibc 2.39 / Node 24 memakai `clone3`, `statx`, `io_uring_setup`, `rseq`,
   `faccessat2`, `openat2`, `close_range`, `set_robust_list`. proot-me meneruskan SIGSYS itu dan proses mati; fork Termux
   menangkapnya (`src/tracee/seccomp.c`), mengemulasi yang umum dan mengembalikan `ENOSYS` untuk sisanya.
2. **Dibangun dengan NDK, bukan glibc statis.** Build glibc statis (di chroot Ubuntu) mati diam-diam di ponsel tanpa keluaran;
   proot bionic adalah proses Android biasa seperti yang dipakai Termux.

`proot-exec.sh` meniru `proot-distro login`: `--kill-on-exit --link2symlink --sysvipc -L -0`, binding `/dev`, `/dev/pts`, `/proc`,
`/sys`, `/dev/shm` (direktori aplikasi), `/dev/urandom:/dev/random`, `/dev/fd` dan `/dev/std*` bila tidak ada, serta salinan statis
untuk `/proc/loadavg`, `/proc/stat`, `/proc/uptime`, `/proc/version`, `/proc/vmstat`, beberapa `/proc/sys/*`, dan `/sys/fs/selinux`
kosong bila Android menyembunyikannya dari aplikasi. Android hanya mengizinkan eksekusi dari `nativeLibraryDir`, karena itu biner
dikemas sebagai native library (`jniLibs.useLegacyPackaging = true`).
