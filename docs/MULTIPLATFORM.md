# Persiapan Windows, Linux, dan iOS

Status: rencana (belum ada kode multiplatform). Dokumen ini mencatat apa yang bisa dipakai ulang, apa yang tidak,
dan urutan kerja yang aman.

## 1. Kenyataan yang menentukan arsitektur

Inti produk saat ini adalah **IDE di dalam HP**: chroot Ubuntu dengan root (`su -mm`), agent berjalan di sana,
build Android dilakukan di sana. Tiga hal itu tidak ada di platform lain:

| Platform | Bisa menjalankan proses/shell lokal | Pengganti chroot | Bentuk aplikasi yang realistis |
|---|---|---|---|
| Android (sekarang) | Ya (root) | chroot Ubuntu | IDE lengkap di perangkat |
| Linux desktop | Ya | langsung di host, atau Docker/Podman | IDE lengkap |
| Windows desktop | Ya | WSL2 (Ubuntu), atau Docker | IDE lengkap lewat WSL2 |
| iOS | **Tidak** (tidak ada `fork/exec` bebas, tidak ada root, tidak boleh mengunduh dan menjalankan kode baru) | tidak ada | **Klien jarak jauh**: terhubung ke mesin yang menjalankan agent (desktop, server, atau HP Android) |

Kesimpulan: iOS tidak bisa menjadi IDE on-device. Targetnya klien: chat, melihat file/diff, menyetujui tool,
dengan agent berjalan di tempat lain.

## 2. Seberapa siap kode sekarang

Hitungan dari `app/src/main/java/com/nextcraft/forge` (file `.kt`, diukur dari import `android.*`, `androidx.*`,
`com.termux.*`):

| Paket | File | Bergantung Android | Tidak bergantung |
|---|---|---|---|
| `model` | 2 | 0 | 2 |
| `network` | 3 | 2 | 1 |
| `data` | 2 | 2 | 0 |
| `runtime` | 34 | 26 | 8 |
| `ui` | 50 | 47 | 3 |
| `update` | 1 | 1 | 0 |

- Yang sudah murni (kandidat pertama untuk modul bersama): `model/*`, `ShellQuote`, `SnapshotManager`,
  `RuntimeCompatibility`, `RootfsRepair`, `ReaResult`, `OpenRouterRoutingGateway`, `AndroidToolchain`,
  `AndroidProjectTemplateGenerator`, `LocalNetwork`.
- `ui` (±17.900 baris) memakai Jetpack Compose. Compose Multiplatform memakai API yang hampir sama, jadi sebagian
  besar bisa dipindah, tetapi 47 dari 50 file masih mengimpor `android.*`/`androidx.*` dan perlu dibersihkan satu per satu.
- `runtime` terikat pada Android Service, `ProcessBuilder` lewat `su`, JNI `libforgespawn`, dan terminal Termux.
- **REA (Python) sudah lintas platform**: paket stdlib, kontrak JSON schema 1. Hanya pemanggilnya (`ReaController`) yang
  terikat chroot.

## 3. Rekomendasi

**Kotlin Multiplatform + Compose Multiplatform**, bukan menulis ulang di Flutter/Tauri/Electron.

Alasan: UI dan logika sudah Kotlin/Compose (±31.000 baris di `runtime` dan `ui`); menulis ulang berarti membuang itu.
Konsekuensi: iOS lewat Compose Multiplatform (stabil sejak 1.8) butuh Mac dan Xcode untuk build, dan CI memakai runner `macos`.

Alternatif yang ditolak untuk sekarang:

| Opsi | Kelebihan | Kenapa tidak sekarang |
|---|---|---|
| Flutter | UI satu basis kode, iOS matang | Tulis ulang semua; logika agent harus dibuat ulang di Dart |
| Tauri/Electron + web | Desktop cepat | Tidak membantu iOS; UI Compose dibuang |
| Hanya klien web | Satu klien untuk semua | Tidak ada pengalaman native; tetap perlu server |

## 4. Urutan kerja (setiap fase bisa dirilis dan diuji sendiri)

**Fase 0 — Batas antarmuka (tanpa mengubah perilaku).**
Definisikan antarmuka `ExecutionBackend` (jalankan perintah, baca/tulis file, stream output, hentikan) dan
`PlatformServices` (penyimpanan kunci, notifikasi, pembaruan aplikasi, pemilih file). Implementasi Android = kode
sekarang (`ChrootRunner`, `RuntimeExecutionService`, `ApiKeyVault`). `MainViewModel` hanya bicara ke antarmuka.
Kriteria: semua tes yang ada tetap lulus, tidak ada perubahan di layar.

**Fase 1 — Modul `:core`.**
Pindahkan file murni (bagian 2) ke modul Kotlin/JVM, lalu ke KMP (`commonMain`). Ganti `org.json` dan API
`java.*` yang tidak ada di Kotlin/Native dengan `kotlinx.serialization` dan `okio`/`kotlinx-io`.
Kriteria: `:core` terkompilasi untuk `androidTarget` dan `jvm("desktop")`; tes `:core` jalan di CI Linux.

**Fase 2 — Desktop (Linux dulu, lalu Windows).**
Modul `:desktop` (Compose Desktop). Backend: Linux = proses lokal; Windows = `wsl.exe -d Ubuntu -- ...`; opsi
Docker/Podman di keduanya. Tanpa JNI `libforgespawn`; pakai `ProcessBuilder`. Paket distribusi: `.deb`/AppImage dan `.msi`
lewat `jpackage` (task `packageDistributionForCurrentOS`). Penandatanganan tidak diatur sampai ada sertifikat.
Kriteria: buka proyek, kirim pesan ke Claude Code, lihat diff, pada Ubuntu 24.04 dan Windows 11 + WSL2.

**Fase 3 — iOS klien jarak jauh.**
Protokol agent: sekarang bridge berjalan di proses yang sama. Perlu server ringan (HTTP/WebSocket dengan token) yang
dipasang di mesin agent, memakai kontrak peristiwa yang sudah ada (`RuntimeEvent`). Aplikasi iOS hanya klien.
Kriteria: dari iPhone, kirim pesan ke agent yang berjalan di desktop atau HP Android dalam satu jaringan.

**Fase 4 — Rilis.**
Workflow CI per platform (Linux, Windows, macOS untuk iOS). iOS butuh akun Apple Developer (US$99/tahun) dan
TestFlight; tidak bisa dibagikan sebagai file seperti APK.

## 5. Risiko dan hal yang perlu diputuskan

1. **Keamanan protokol jarak jauh (Fase 3).** Menjalankan agent dengan akses shell lewat jaringan adalah permukaan serangan.
   Wajib: autentikasi token, TLS atau tunnel SSH, default hanya localhost. Perlu desain sendiri sebelum kode.
2. **Kunci API.** `ApiKeyVault` memakai Android Keystore. Desktop: Windows Credential Manager / libsecret; iOS: Keychain.
3. **Terminal.** Termux terminal view hanya Android. Desktop butuh emulator terminal lain (mis. JediTerm); iOS tidak memerlukannya.
4. **Aturan App Store.** Aplikasi yang menjalankan kode unduhan di perangkat ditolak; klien jarak jauh dapat diterima, tetapi
   ini perlu dicek saat review.
5. **Biaya.** Fase 0–2 ±beberapa minggu kerja bertahap; iOS (Fase 3) bergantung pada Mac dan akun developer.

## 6. Yang belum dikerjakan

Tidak ada perubahan Gradle, tidak ada modul baru. Alasannya: memecah modul sebelum antarmuka Fase 0 ada akan
memindahkan kode tanpa memutus ketergantungan Android, dan berisiko merusak build yang sekarang hijau.
