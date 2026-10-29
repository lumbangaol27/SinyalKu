# SinyalKu

Aplikasi Android untuk memantau sinyal dan hotspot satu HP dari HP lain milik sendiri. Dipasang di dua HP:

- **HP hotspot (HP A):** HP yang menyalakan hotspot dan berisi kartu SIM. Menjalankan layanan latar dengan server status kecil.
- **HP pemantau (HP B):** tersambung ke hotspot HP A. Membaca status HP A tiap beberapa detik dan memberi peringatan.

## Cara pakai
1. Pasang APK di kedua HP (unduh dari Releases, tag `apk-terbaru`).
2. Di HP A: buka aplikasi, pilih **Pakai HP ini sebagai HP hotspot**, izinkan notifikasi dan telepon, tekan **Mulai layanan**, lalu tekan **Izinkan berjalan terus di latar belakang**. Catat PIN 6 angka.
3. Nyalakan hotspot di HP A.
4. Di HP B: sambungkan WiFi ke hotspot HP A, buka aplikasi, pilih **HP pemantau**, isi PIN (alamat boleh kosong, otomatis), tekan **Mulai memantau**.

## Yang ditampilkan
Sinyal seluler HP A (dBm, batang, jenis jaringan, operator), baterai dan suhu, status hotspot dan perangkat tersambung (sebisa Android), pemakaian data hari ini dan kecepatan, kekuatan WiFi HP B ke HP A beserta waktu tempuh.

## Peringatan di HP B
HP A tidak terjangkau (3 kali berturut-turut), sinyal lemah, hotspot mati, baterai rendah, batas data harian. Ambangnya bisa diatur di **Pengaturan peringatan**.

## Perintah ke HP A
- **Bunyikan HP A** (untuk mencari HP).
- **Minta HP A membuka pengaturan hotspot:** HP A menerima notifikasi yang sekali ketuk membuka pengaturan hotspot. Android tidak mengizinkan aplikasi biasa menyalakan atau mematikan hotspot sendiri.

## Keamanan
Server di HP A hanya menjawab permintaan yang membawa PIN yang benar (header `X-Pin`); lima PIN salah dari satu alamat memblokirnya satu menit. Lalu lintas memakai HTTP biasa di jaringan hotspot sendiri. Pakai hanya di HP milik sendiri atau yang pemiliknya setuju.

## Batasan yang diketahui
- Jika hotspot HP A mati, HP B yang tersambung lewat hotspot itu ikut putus; yang tampil hanya "tidak terjangkau".
- Daftar perangkat tersambung memakai `/proc/net/arp` dan `ip neigh`; Android 10 ke atas sering membatasinya, jadi bisa kosong.
- Layanan tidak otomatis hidup setelah HP dinyalakan ulang.
