# KerjaCam

Kamera + galeri khusus kerja (terpisah dari galeri pribadi). Kotlin, Jetpack Compose, CameraX.

## Cara menjalankan
1. Buka folder ini di Android Studio (Koala / 2024.1 atau lebih baru).
2. Tunggu Gradle sync (butuh internet). Jika diminta, izinkan Android Studio membuat Gradle wrapper.
3. Pilih perangkat/emulator (Android 7.0+), lalu klik Run.

## Fitur
- Ruang (proyek/lokasi) -> Koleksi -> Foto
- Kamera dalam aplikasi (CameraX); pilih Koleksi sebelum memotret, bisa ganti antar jepretan
- Foto tersimpan di penyimpanan privat aplikasi, tidak muncul di galeri perangkat
- Catatan per foto, pencarian catatan dan nama koleksi
- Impor foto dari galeri (Photo Picker, tanpa izin storage)
- Bagikan foto, ekspor foto/koleksi ke galeri (Pictures/KerjaCam/<koleksi>)
- Tanpa akun, offline

## Struktur
- Repo.kt          model data + penyimpanan JSON lokal
- MainActivity.kt  semua layar Compose
