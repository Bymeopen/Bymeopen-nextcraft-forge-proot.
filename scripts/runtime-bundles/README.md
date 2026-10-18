# NextCraft Forge Runtime Scripts & Bundle Builders (`Pure Root Chroot Edition`)

Direktori ini berisi dua kelompok skrip untuk **NextCraft Forge (`com.nextcraft.forge`)**:

1. **On-Device Root Chroot Supervisor Scripts** (dipasang ke `/data/data/com.nextcraft.forge/files/runtime/bin/`):
   - `chroot-exec.sh`: Supervisor eksekusi `su -mm` yang mengelola *idempotent kernel mounts* (`/dev`, `/dev/pts`, `/proc`, `/sys`, `/tmp`, `/dev/shm`), *read-only bind mounts* partisi Android (`/system`, `/apex`, `/vendor`, `/product` untuk binary Bionic `aapt2`), *dynamic workspace & bridge bind mounts* (`/workspace/nextcraft`, `/nextcraft-bridge`), pembersihan variabel environment Bionic tanpa menghapus variabel AI/Gateway, kompatibilitas `GLIBC_TUNABLES=glibc.pthread.rseq=0`, dan *Exit Trap* `chown -R $APP_UID:$APP_UID`.
   - `unmount-chroot.sh`: Guard pelepas seluruh mount kernel secara terbalik (`sort -r` pada `/proc/mounts`) dan memverifikasi 0 mount tersisa sebelum operasi `RootfsRepair` diizinkan menghapus atau mengganti direktori rootfs.

2. **Reproducible Runtime Bundle Builders** (untuk menghasilkan artefak `.tar.zst` deterministik):
   - `build-core-on-rooted-android.sh`: Membangun bundel `core` dari image resmi **Ubuntu 24.04.5 LTS (`noble`) ARM64** (`ubuntu-base-24.04.5-base-arm64.tar.gz`, SHA-256 terverifikasi), Node.js `v24.19.0`, `git`, `ripgrep`, `jq`, serta konfigurasi jaringan kernel Android (`aid_inet:x:3003:root,_apt` dan `99android-chroot`).
   - `build-agy-from-official.sh`: Mengemas binary resmi Linux ARM64 **Antigravity CLI (`agy`)** dengan verifikasi `SHA-512` (tanpa ponsel).
   - `build-claude-from-official.sh`: Membangun bundel **Claude Code** tanpa ponsel dari rilis resmi Linux ARM64 (`downloads.claude.ai`), dicek terhadap checksum `manifest.json` rilis **dan** SHA-256 yang dipin.
   - `build-dsh-in-chroot.sh`: Membangun bundel **DeepSeek Harness** tanpa ponsel dengan `npm install` yang sama seperti `RuntimeInstaller`, di dalam salinan chroot rootfs Core (native ARM64 atau `qemu-user-static` + binfmt flag `F` di x86_64). Hasilnya bisa berbeda antar hari karena npm me-resolve rentang dependensi; pin hasilnya lewat SHA-256.
   - `build-python-bundle.sh`: Membangun overlay **Python 3.12** (`python3`, `pip`, `venv`) di atas rootfs Core 24.04.5 dengan cara chroot yang sama; reproducible.
   - `build-claude-from-installed-android.sh`: Mengekspor binary **Claude Code (`claude`)** ARM64 terverifikasi `SHA-256` tanpa kredensial atau data pengguna.
   - `build-dsh-from-installed-android.sh`: Mengemas payload npm **DeepSeek Harness (`dsh`)** beserta symlink peluncurnya.
   - `create_deterministic_tar.py`: Menghasilkan arsip GNU tar yang 100% deterministik (`uid=0`, `gid=0`, `mtime=2026-09-06T00:00:00Z`, urutan alfabetis, mempertahankan symlink `followlinks=False` / `dereference=False`, dan membuang metadata macOS `._*`).

Skrip berbasis chroot (`build-python-bundle.sh`, `build-dsh-in-chroot.sh`) butuh root dan `NEXTCRAFT_CORE_ROOTFS` yang menunjuk ke bundel Core hasil ekstrak; skrip hanya membaca direktori itu dan bekerja di salinannya. Semua builder menulis ke `dist/runtime-bundles/` (di-ignore git); arsipnya diunggah sebagai asset release, bukan di-commit.
