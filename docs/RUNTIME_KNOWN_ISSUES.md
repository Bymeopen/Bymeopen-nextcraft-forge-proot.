# Runtime 24.04 — Masalah yang Diketahui dan Rencana Perbaikan

Status per 2026-10-18, dari uji di perangkat root (kernel 5.10 GKI, Ubuntu 24.04.5 arm64, glibc 2.39) memakai
`scripts/runtime-bundles/chroot-health.sh` dan diagnosa manual di terminal chroot.

## Harus diperbaiki

### 1. `aapt2` dan `zipalign` tidak bisa jalan: `/apex` di-bind tanpa submount (kritis)

**Gejala:** pemasangan stack Android gagal dengan
`/root/android-sdk/build-tools/35.0.0/aapt2: cannot execute: required file not found`.

**Bukti (terminal chroot):**

```
/system/bin/linker64 -> /apex/com.android.runtime/bin/linker64
tmpfs /apex tmpfs ro,seclabel,nosuid,nodev,noexec,relatime,...   # satu-satunya baris /apex
ERR  apex submounts   0 submount
```

**Akar masalah:** `aapt2` dan `zipalign` adalah biner Bionic dengan interpreter `/system/bin/linker64`, yang di
Android 10+ adalah symlink ke `/apex/com.android.runtime/bin/linker64`. `/apex` adalah tmpfs, dan tiap modul APEX
adalah mount tersendiri di bawahnya. `chroot-exec.sh` memasang `/system`, `/apex`, `/vendor`, `/product` dengan
`mount -o bind` (non-rekursif), sehingga di dalam chroot `/apex` hanya berisi direktori kosong. Linker tidak
ditemukan, dan kernel mengembalikan `ENOENT` untuk interpreter ELF.

**Perbaikan:**

- Di kedua salinan `chroot-exec.sh` (string `CHROOT_EXEC_SCRIPT` di `RuntimeInstaller.kt` dan
  `scripts/runtime-bundles/chroot-exec.sh`), ganti bind keempat direktori Bionic menjadi rekursif lalu
  `rslave`, agar unmount di dalam chroot tidak merambat ke namespace Android:
  ```sh
  mount -o rbind "$bionic_dir" "$ROOTFS$bionic_dir"
  mount --make-rslave "$ROOTFS$bionic_dir" 2>/dev/null || true
  mount -o remount,bind,ro "$ROOTFS$bionic_dir" 2>/dev/null || true
  ```
- `is_mounted` hanya memeriksa titik mount teratas. Mount lama yang sudah ada (non-rekursif dari versi
  sebelumnya) tidak akan diperbaiki otomatis. Tambahkan deteksi: bila `/apex` ter-mount tetapi tidak punya
  submount, lepas (`umount -l`) lalu pasang ulang dengan `rbind`.
- Pastikan `unmount-chroot.sh` dan `RootfsRepair.ensureUnmounted` melepas semua submount `/apex/*` (urutan
  terbalik) sebelum rootfs dihapus atau diganti.
- Verifikasi di perangkat: `chroot-health.sh` harus menunjukkan `apex submounts` > 0, `linker64` OK,
  `aapt2 version` OK, `zipalign` OK, lalu build APK template berhasil.

### 2. Kegagalan satu stack menghentikan stack berikutnya

**Gejala:** toggle C/C++ dan PHP aktif, tetapi `gcc`, `make`, `cmake`, `gdb`, `php`, dan `composer` tidak
terpasang setelah verifikasi Android gagal.

**Perlu dicek:** urutan dan penanganan error di loop pemasangan stack (`ensureInstalled` →
`missingStacks`, `ensureStackInstalled`). Bila memang berhenti di stack pertama yang gagal, pilihannya:
lanjutkan stack lain dan laporkan semua kegagalan di akhir, atau tampilkan dengan jelas stack mana yang
belum dipasang.

### 3. Skrip init Gradle di dalam bundle Android rusak (kecil)

`root/.gradle/init.d/pocketdev-android.gradle` di `pocketdev-android-arm64-2026.09.1.tar.zst` kehilangan tanda
kutip string, sehingga Gradle gagal mengompilasinya. Saat ini tidak berdampak karena
`writeAndroidGradleInitScript` selalu menimpanya setelah overlay dan saat peluncuran. Perbaiki saat bundle Android
berikutnya dibangun.

## Belum terverifikasi

- Fungsi nyata agen (Claude Code, DSH, Antigravity) di perangkat. Di uji ini belum dipasang karena belum dipilih.
- `gh` (dipasang lewat tombol **Hubungkan** GitHub).
- Build APK end-to-end (bergantung pada perbaikan nomor 1).
- Hasil DSH `npm install` berubah bila dibangun di hari lain; pin hasilnya lewat SHA-256.
- Migrasi pengguna lama dari rootfs 20.04 ke 24.04: belum ada jalurnya, pengguna lama sengaja tetap di 20.04.

## Cara cek ulang

Di terminal chroot aplikasi:

```bash
bash /path/ke/chroot-health.sh 2>&1 | tee chroot-health.log
```

ERR pada stack yang belum dipasang adalah wajar. ERR pada stack yang terpasang adalah masalah nyata.
