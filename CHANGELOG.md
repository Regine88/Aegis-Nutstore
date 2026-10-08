# Changelog

This changelog covers the changes made in this fork. The upstream project keeps its own
release notes at https://github.com/beemdevelopment/Aegis/releases.

## 2026-10-08 – Nutstore (Jianguoyun) encrypted backup

Base: Aegis 3.4.3 (GPL-3.0). Modifications:

- Added an optional cloud backup target: encrypted backups are uploaded to Nutstore
  (Jianguoyun) over WebDAV (`https://dav.jianguoyun.com/dav/`).
- Added a hardened WebDAV client (MKCOL, PROPFIND, PUT, GET, DELETE) with redirect, href,
  size, timeout and cancellation handling, and classified errors.
- Added persistent background uploads through WorkManager: immutable snapshots in
  `noBackupFilesDir`, same-name idempotent retries, GET + SHA-256 verification before a
  version is confirmed, exponential backoff with Retry-After awareness, retry limits and
  remote pruning limited to the app's own device directory.
- Added a dedicated Android Keystore credential store that works while the vault is locked
  and stays out of Android system backups, SharedPreferences and task input data.
- Added the Nutstore settings screen (account, application password, remote directory,
  retention, connection test, status, version list, restore) and a "Restore from Nutstore"
  entry on the welcome screen for fresh installs.
- Added 59 unit tests (credentials, WebDAV protocol with a simulated server, snapshots,
  upload pipeline, scheduling/cleanup) and two opt-in device tests, one of which runs a real
  upload/download/decrypt cycle against a real Nutstore account.
- Fixed a defect found during live device testing: Android's XML parser rejects
  `DocumentBuilderFactory.setXIncludeAware`, which would crash the first PROPFIND. The parser
  configuration now tolerates unsupported platform features.
- Documented the design, implementation plan and validation results in `docs/`.
