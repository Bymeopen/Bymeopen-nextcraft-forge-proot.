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
- Semua berkas rootfs milik UID aplikasi, jadi *Disconnect GitHub*, *Sign out*, dan perbaikan rootfs tidak butuh eskalasi.

## Asal biner

`app/src/main/jniLibs/arm64-v8a/libproot.so` (+ `libproot_loader.so`, loader ELF yang dirujuk `PROOT_LOADER`) = **`termux/proot`**
commit `e2d843ee6dab076ea0ed1ffd5d0ea12232185499` + `talloc` 2.4.3, dibangun statis (glibc) oleh
`scripts/runtime-bundles/build-proot-static.sh` di chroot Core 24.04 ARM64 (di mesin pengembang, bukan di ponsel).
SHA-256 `libproot.so` `233a2109c3130cfcc89b0c41a5834f56d16b7f3465058f3c3829a19139289a62` (1.070.568 byte),
`libproot_loader.so` `71b4563794901787888a46830ad7ca6838902c7aa33acec9e5351de42639eb2c` (67.528 byte).

Mengapa fork Termux, bukan `proot-me/proot`: filter seccomp aplikasi Android menjawab setiap syscall di luar daftar izin
bionic dengan SIGSYS (exit 159), bukan `ENOSYS`. Yang kena di guest glibc 2.39 / Node 24 antara lain `clone3`
(`pthread_create`), `statx` (libuv `fs.stat`), `io_uring_setup`, `rseq`, `faccessat2`, `openat2`, `close_range`,
`set_robust_list`. proot-me meneruskan SIGSYS itu dan proses guest mati; fork Termux menangkapnya
(`src/tracee/seccomp.c`), mengemulasi yang umum (`statx`, `set_robust_list`, `chown`, `symlink`, `link`, `utimes`,
`mount`, …) dan mengembalikan `ENOSYS` untuk sisanya sehingga glibc dan libuv memakai jalur cadangan mereka.
Android hanya mengizinkan eksekusi dari `nativeLibraryDir`, karena itu biner dikemas sebagai native library
(`jniLibs.useLegacyPackaging = true`).
