# Deploy ke GitHub Pages dan Render

GitHub Pages hanya menyajikan file statis. Aplikasi ini memakai Render untuk menjalankan API Java serta menyimpan materi pada persistent disk.

## Backend Java di Render

1. Push folder proyek ke repository GitHub.
2. Di Render, pilih **New > Blueprint** dan hubungkan repository ini. Render akan membaca `render.yaml` dan membuat layanan API beserta disk persisten.
3. Saat diminta mengisi environment variables, isi:
   - `ADMIN_PASSWORD`: password admin yang kuat dan rahasia. Jangan commit nilainya ke repository.
   - `ALLOWED_ORIGIN`: origin situs GitHub Pages, misalnya `https://felixx19.github.io` (tanpa path atau slash di akhir). Ganti jika memakai alamat Pages atau domain lain.
4. Setelah layanan aktif, buka `https://<nama-layanan>.onrender.com/api/health`; respons `{"status":"ok"}` menandakan API aktif.

Materi awal disalin ke disk persisten saat pertama kali layanan berjalan. Upload dan penghapusan selanjutnya tetap tersimpan pada disk Render, termasuk setelah restart atau deploy ulang. Disk Render memerlukan paket berbayar.

## Frontend di GitHub Pages

1. Salin URL layanan Render ke `api-config.js`:

   ```js
   window.MODULE_API_BASE = 'https://<nama-layanan>.onrender.com';
   ```

2. Commit dan push perubahan, lalu aktifkan GitHub Pages untuk repository. Buka `modul.html` pada alamat Pages.
3. Login admin memakai username `Admin_Root` dan password yang disetel pada `ADMIN_PASSWORD`. Login selain admin tetap mendapat akses tamu.

Untuk menjalankan lokal, biarkan `api-config.js` kosong, jalankan `java server.java` dari folder proyek, lalu buka `http://127.0.0.1:8000/modul.html`. Password admin lokal default-nya `password`.
