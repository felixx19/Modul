# Deployment Frontend Statis

Aplikasi ini dapat di-host sebagai situs statis, misalnya menggunakan GitHub Pages. Tidak ada server/API yang diperlukan.

## Menjalankan dan deploy

1. Buka `modul.html` melalui server statis lokal atau situs hosting statis.
2. Untuk GitHub Pages, push folder proyek ke repository lalu aktifkan Pages pada repository tersebut.
3. Materi bawaan di folder `Source/Mentah/` tersedia untuk diunduh sebagai file statis.

## Login dan pengelolaan materi lokal

- Login admin lokal: username `Admin_Root`, password `password`. Login ini hanya untuk membedakan tampilan, bukan mekanisme keamanan.
- Tamu dapat melihat dan mengunduh materi, tetapi tidak mendapatkan kontrol tambah/hapus.
- Materi yang ditambahkan dan perubahan daftar materi disimpan di penyimpanan browser (IndexedDB dan local storage) pada perangkat/browser yang digunakan. Materi tersebut tidak diunggah ke situs dan tidak tersedia bagi pengunjung lain.
- Admin yang menghapus materi bawaan hanya menyembunyikannya dari daftar pada browser tersebut; file situs aslinya tidak dihapus.
- Perubahan daftar ditampilkan setelah halaman dimuat ulang. Menghapus data situs/browser dapat menghilangkan materi tambahan dan perubahan daftar.

Jangan gunakan mode admin lokal untuk melindungi data atau mengelola materi pada situs publik. Pengguna dapat membaca kode frontend dan melewati pembatasan peran karena tidak ada backend.
