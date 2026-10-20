# Merilis aplikasi dan update dalam aplikasi

Cek update di Settings membaca `https://github.com/bymeopen/nextcraft-forge-proot/releases/latest/download/update-manifest.json`.

## Rilis lewat GitHub Actions

1. Tambahkan secret repositori (kunci yang sama dengan aplikasi yang terpasang, atau update akan ditolak karena tanda tangan beda):
   `FORGE_KEYSTORE_BASE64` (`base64 -w0 forge.keystore`), `FORGE_KEYSTORE_PASSWORD`, `FORGE_KEY_ALIAS`, `FORGE_KEY_PASSWORD`.
2. Actions → **Release app** → Run workflow, isi tag (mis. `v1.0.1`), `version_code` (harus lebih besar dari build terpasang; saat ini 100), `version_name`.
3. Workflow membangun APK release bertanda tangan, membuat `update-manifest.json` (`scripts/make-update-manifest.sh`) dan menerbitkan keduanya sebagai rilis **latest**.

Rilis runtime (`runtime-*`) tidak boleh menjadi rilis "latest": tandai sebagai pre-release atau terbitkan lebih dulu, supaya cek update tidak membaca release yang salah.

## Manual

```bash
FORGE_KEYSTORE_FILE=... FORGE_KEYSTORE_PASSWORD=... FORGE_KEY_ALIAS=... FORGE_KEY_PASSWORD=... \
  gradle :app:assembleRelease -PversionCode=101 -PversionName=1.0.1
scripts/make-update-manifest.sh app/build/outputs/apk/release/app-release.apk 101 1.0.1 v1.0.1
```

Unggah APK dan `update-manifest.json` ke rilis `v1.0.1`, lalu tandai sebagai latest.

## CI

`.github/workflows/ci.yml` menjalankan unit test, `assembleDebug`, cek sintaks skrip, dan memastikan
`scripts/runtime-bundles/{chroot-exec,unmount-chroot}.sh` sama dengan string yang dikirim di `RuntimeInstaller.kt`
(`python3 scripts/check-embedded-scripts.py --write` untuk membuat ulang).

Bundle runtime (`runtime-*`) **tidak** dirilis di repo ini: `RUNTIME_RELEASE_BASE_URL` di `RuntimeInstaller.kt` tetap menunjuk ke rilis `bymeopen/nextcraft-forge`, sehingga kedua aplikasi memakai bundle yang sama.
