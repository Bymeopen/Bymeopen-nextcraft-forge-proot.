# Runtime 24.04 — Masalah yang Diketahui dan Rencana Perbaikan

Status per 2026-10-18, dari uji di perangkat root (kernel 5.10 GKI, Ubuntu 24.04.5 arm64, glibc 2.39) memakai
`scripts/runtime-bundles/chroot-health.sh` dan diagnosa manual di terminal chroot.

## Status

| # | Masalah | Status |
|---|---|---|
| 1 | `/apex` tanpa submount, `aapt2`/`zipalign` gagal | Diperbaiki di kode, belum diverifikasi di perangkat |
| 2 | Satu stack gagal menghentikan stack lain | Diperbaiki |
| 3 | Skrip init Gradle rusak di bundle Android | Belum; tidak berdampak |

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

**Perbaikan (diterapkan):** `mount_bionic` di kedua salinan `chroot-exec.sh` (string `CHROOT_EXEC_SCRIPT` di
`RuntimeInstaller.kt` dan `scripts/runtime-bundles/chroot-exec.sh`):

1. Bind `/system`, `/apex`, `/vendor`, `/product` tanpa rekursi (belum ada submount, jadi aman).
2. Jadikan mount itu `slave` (`--make-rslave`, dengan cadangan `-o rslave`/`--make-slave`/`-o slave`). Bila
   semuanya gagal, berhenti di bind lama dan tampilkan peringatan: `aapt2` tetap gagal, tetapi Android aman.
3. Bind setiap submount (modul APEX) satu per satu, induk lebih dulu, masing-masing langsung dijadikan `slave`.
4. Mount lama yang non-rekursif (dari versi sebelumnya) dideteksi dan diganti otomatis.

`rbind` sengaja **tidak** dipakai: salinannya adalah peer dari mount Android yang *shared* sampai dijadikan
slave, dan `umount` salinan yang masih peer ikut melepas modul APEX asli. Sebagai lapisan kedua,
`unmount-chroot.sh` (kedua salinan) dan `RootfsRepair.ensureUnmounted` menjadikan semua mount di bawah rootfs
`private` sebelum melepasnya.

**Diuji** di Linux dengan `/apex` tiruan yang *shared* (tmpfs + submount modul), dijalankan dengan `dash`:
linker terlihat di dalam rootfs, salinan tidak lagi *shared*, mount lama terganti, kedua skrip unmount melepas
semuanya tanpa menyentuh modul asli, dan jalur cadangan tanpa `rbind`/`--make-rslave` juga lulus. Uji kontrol
memastikan risikonya nyata: `rbind` tanpa `slave` lalu `umount` memang melepas modul asli.

**Belum diverifikasi di perangkat:** dukungan opsi propagasi di `mount` milik toybox/busybox Magisk/KernelSU.
Setelah update, `chroot-health.sh` harus menunjukkan `apex submounts` > 0, `linker64` OK, `aapt2` dan
`zipalign` OK, lalu build APK template berhasil. Bila muncul peringatan "mount propagation cannot be changed",
`mount` di perangkat tidak mendukung opsi propagasi.

### 2. Kegagalan satu stack menghentikan stack berikutnya

**Gejala:** toggle C/C++ dan PHP aktif, tetapi `gcc`, `make`, `cmake`, `gdb`, `php`, dan `composer` tidak
terpasang setelah verifikasi Android gagal.

**Akar masalah:** loop pemasangan stack di `ensureInstalled` memanggil `applyStack` berurutan tanpa
penanganan error, sehingga exception dari verifikasi Android menghentikan stack sesudahnya.

**Perbaikan (diterapkan):** setiap stack dipasang dalam `try`; kegagalan dicatat (`Log.e`, pesan progres),
stack berikutnya tetap dipasang, agen tetap dipasang, lalu semua kegagalan dilaporkan sekaligus di akhir.
Pembatalan (`CancellationException`) tetap menghentikan proses. Stack yang gagal tidak ditandai terpasang,
jadi akan dicoba lagi pada pemasangan berikutnya.

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
