# Merilis aplikasi dan update dalam aplikasi

Cek update di Settings membaca `https://github.com/bymeopen/nextcraft-forge/releases/latest/download/update-manifest.json`.

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

## Debug builds (`release-debug.yml`)

Debug and release use the same application id, `com.nextcraft.forge`, so there is one app, one data folder and one
Ubuntu rootfs. The two kinds are signed differently: debug with the public key in `app/debug.keystore` (so CI builds
update each other), release with the `FORGE_KEYSTORE_*` secrets. Android refuses to install one over the other, and the
in-app updater checks the certificate first and says so. Going from one kind to the other means uninstalling, which
deletes the rootfs, chats and root grant; back them up first.

The workflow publishes the rolling `dev-latest` release with `nextcraft-forge-debug-<version>.apk` and a manifest whose
`artifacts.debug` entry is read only by debug builds; a release build finds no `artifacts.release` there and offers
nothing. Start it from the Actions tab (`Release debug build`, pick the branch). Builds before this change installed as
`com.nextcraft.forge.x` and cannot update to this package from inside the app: install the new APK once by hand.
