# Shunno Get (শূন্য গেট)

**Shunno Get** is a fast, ad-free, multi-threaded download manager for Android modeled after Internet Download Manager (IDM) and 1DM, featuring an Apple-inspired Cupertino UI, HLS (`.m3u8`) stream grabbing, bit-perfect FLAC/ALAC lossless audio support, and automated GitHub Actions APK builds.

## 📲 1-Click Direct Download
- **[👉 Download ShunnoGet.apk](https://github.com/asanaim/ShunnoGet/releases/latest/download/ShunnoGet.apk)**

## ✨ Key Features
- **Multi-Threaded HTTP Range Acceleration (1–16 Threads):** Splits files into parallel byte-range coroutines (`Range: bytes=start-end`) writing directly via `RandomAccessFile` with automatic single-stream fallback when a server does not advertise `Accept-Ranges`.
- **True Pause, Resume & Expired Link Repair:** Checkpoints every thread's exact byte offset in local storage and SQLite (`shunnoget.db`), allowing you to swap expired CDN URLs via **Fix Link** without losing downloaded bytes.
- **Built-in Ad-Blocking Browser & Stream Sniffer:** Intercepts network requests in a tabbed WebView to block ad/tracker hosts and capture `.mp4`, `.m3u8`, `.flac`, `.m4a`, `.zip`, and `.apk` links along with active `Cookie` and `Referer` headers.
- **Wildcard Batch Grabber & Wi-Fi Scheduler:** Supports wildcard URL sequences (`track_[01-12].flac`), Android Share-sheet capture, `shunnoget://` deep links, and Wi-Fi-only scheduling.
