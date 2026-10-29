# SinyalKu (repo: lumbangaol27/SinyalKu, privat)

Catatan serah-terima. Pemilik: pengguna berbahasa Indonesia yang **hanya pegang HP**; jawab santai dan singkat, jangan minta ia menjalankan perintah. Proyek kembarannya: `lumbangaol27/peta-kebun` (Peta Lapangan) yang punya `CLAUDE.md` sendiri dengan banyak pelajaran build.

## Tujuan
Memantau sinyal/hotspot satu HP (HP A) dari HP lain (HP B), keduanya milik pemilik. Tidak untuk mengintip HP orang lain: jangan tambahkan fitur tersembunyi, tanpa PIN, atau tanpa pemasangan sadar di kedua HP.

## Arsitektur (Android native, Java, tanpa pustaka tambahan)
- `Logic.java`: logika murni (parse ARP/ip neigh, dBm -> level, format, mesin peringatan `AlertEngine`). **Diuji di komputer**: `test/LogicTest.java` (23 uji), dijalankan di CI sebelum build.
- `Collector.java`: status HP A (sinyal lewat TelephonyCallback/PhoneStateListener, baterai, antarmuka hotspot dari NetworkInterface, perangkat dari ARP, data dari TrafficStats, hitungan harian di SharedPreferences).
- `StatusServer.java`: ServerSocket port 8765. `/hello` tanpa PIN; `/status`, `/cmd?name=ring|stopring|settings` butuh header `X-Pin`. 5 PIN salah = blokir 1 menit per alamat.
- `HotspotService` (HP A) dan `MonitorService` (HP B): foreground service tipe `specialUse`, wake lock dan WiFi lock. `Monitor.java` memegang keadaan terakhir (statik) dan memanggil HTTP.
- `MainActivity.java`: seluruh UI dibuat lewat kode (tiga tampilan: pilih peran, A, B). `Prefs.java`: pengaturan, PIN. `Notif.java`: kanal dan notifikasi.
- minSdk 26, target/compile 34, AGP 8.5.2, Gradle 8.7 (dipasang `gradle/actions/setup-gradle`, tanpa wrapper di repo).

## Build dan rilis
- `.github/workflows/android.yml` jalan otomatis saat push ke `main` atau manual: `gh workflow run android.yml --repo lumbangaol27/SinyalKu --ref main`. Urutan: uji logika, `gradle assembleDebug` (dengan `pipefail`; tanpanya `tee` menutupi kegagalan), cek kunci dengan `apksigner`, unggah artifact, terbitkan Release `apk-terbaru` berisi `SinyalKu_v0.1.<nomor>.apk`.
- Kunci tetap: `debug.keystore` (sandi `android`, alias `androiddebugkey`). **Jangan diganti**, kalau tidak APK baru tidak bisa dipasang di atas yang lama. APK bertanda tangan v2/v3 saja, jadi `keytool -printcert -jarfile` tidak bisa dipakai; pakai `apksigner`.
- Membaca galat build dari sandbox: unduhan log diblokir; baca anotasi: `gh api repos/lumbangaol27/SinyalKu/check-runs/<jobId>/annotations`. Langkah "Tampilkan galat kompilasi" menyalin galat javac ke anotasi.
- Tidak ada Android SDK di sandbox, jadi kode Android hanya terbukti **terkompilasi** di CI, belum teruji di HP.

## Belum terbukti di HP sungguhan (cek dulu kalau pemilik melapor masalah)
Pembacaan dBm/jenis jaringan di berbagai merek, deteksi antarmuka hotspot (`ap0`, `swlan0`, `wlan1`, dll.), pembacaan daftar perangkat (Android 10+ sering kosong), kelangsungan layanan latar saat layar mati (penghemat baterai pabrikan), alamat otomatis dari gateway DHCP, kecepatan data dari TrafficStats.

## Ide berikutnya
Mulai otomatis setelah HP menyala (BootReceiver), grafik riwayat sinyal, ekspor log CSV, nama kustom untuk perangkat tersambung, mode lewat internet (butuh server perantara), ikon adaptif.
