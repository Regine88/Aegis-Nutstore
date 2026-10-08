<img align="left" width="80" height="80" src="metadata/en-US/images/icon.png" alt="App icon">

# Aegis with Nutstore (Jianguoyun) cloud backups

<br>

> **Unofficial fork.** This repository is a fork of
> [Aegis Authenticator](https://github.com/beemdevelopment/Aegis) based on version 3.4.3.
> It is not affiliated with or endorsed by the upstream project or by Nutstore. All of the
> base features described in the [upstream README](https://github.com/beemdevelopment/Aegis#readme)
> are still available; this fork adds an optional encrypted cloud backup target.

**Aegis** is a free, secure and open source 2FA app for Android. It stores the vault encrypted
with AES-256-GCM and scrypt, and supports HOTP/TOTP, biometric unlock, encrypted exports and
local/Android backups.

This fork adds **automatic encrypted backups to [Nutstore](https://www.jianguoyun.com/)
(坚果云) over WebDAV**, plus manual restore from the cloud.

## What this fork adds

- **Automatic encrypted backups.** After every change the vault is exported in the standard
  Aegis backup format (encrypted, biometric slots stripped, independent backup password
  respected) and uploaded to the configured Nutstore account over
  `https://dav.jianguoyun.com/dav/`.
- **Manual restore.** Browse the device directories and backup versions stored on Nutstore,
  download a version and import it through the existing Aegis import flow (entry selection,
  duplicate detection, explicit wipe confirmation). On a fresh install the welcome screen has
  a "Restore from Nutstore" entry.
- **Background uploads.** WorkManager keeps requests across process death. Snapshots are
  written atomically to `noBackupFilesDir` (never to the cache directory) and can be uploaded
  while the vault is locked.
- **Verified, idempotent uploads.** Every upload is verified by downloading the file again and
  comparing a SHA-256 digest before it is recorded as successful. Retries reuse the same
  remote file name; old remote versions are pruned only inside the app's own device directory
  and only after a complete directory listing was received.
- **Credential hygiene.** The Nutstore application password is encrypted with a dedicated
  Android Keystore alias that does not require user authentication, and stored in a private
  file inside `noBackupFilesDir`. It never enters SharedPreferences, Android system backups,
  WorkManager input data, logs or the exported vault.
- **Hardened WebDAV client.** Fixed HTTPS endpoint, no redirects, re-validation of every href
  returned by the server, request/XML/download size limits, timeouts, cancellation and
  classified errors (authentication, permission, quota, rate limiting, server, network).

Design and verification documents (Chinese):

- [Feature and design](docs/jianguoyun-backup-plan.md)
- [Implementation plan and progress](docs/jianguoyun-development-plan.md)
- [Validation record](docs/jianguoyun-validation.md)

## How to use

1. Create an application password in Nutstore ("Security settings" → third-party
   applications). The main account password is not used by this app.
2. Open **Settings → Backups → Nutstore (Jianguoyun) backups**, enter the account, the
   application password and a remote directory (default `Aegis`), then use
   **Test connection** to verify directory and write access.
3. Enable **Automatic backup**. Only encrypted vaults can be uploaded; it is recommended to
   set a backup password (or remember the vault password) because restores need it.
4. To restore, open the same screen, refresh the version list and tap a version. The standard
   import flow asks for the backup password. On a fresh install use
   **Restore from Nutstore** on the welcome screen.

### 中文说明

- 本仓库是 [Aegis](https://github.com/beemdevelopment/Aegis) 3.4.3 的非官方分支，新增了
  **坚果云（WebDAV）自动加密备份与手动恢复**功能，遵循上游的 GPL-3.0 许可证。
- 使用方式：设置 → 备份 → 坚果云备份，填写坚果云账号与在“安全设置 → 第三方应用管理”
  创建的应用密码，点击“测试连接”确认目录可写后开启自动备份。
- 只有加密后的数据库才会被上传；备份文件是标准 Aegis 加密格式，可用数据库密码或独立
  备份密码恢复。新安装可点欢迎页的“从坚果云恢复”。
- 应用密码使用独立的 Android Keystore 密钥加密后保存在不参与系统备份的私有文件中，
  不会写入日志、系统备份或导出的数据库。

## Building

Requirements: JDK 21 and Android SDK 36 (`ANDROID_HOME` or `local.properties`).

```shell
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests
./gradlew lintDebug            # Android lint
./gradlew build                # what CI runs (debug + release variants)
```

Optional live integration test against a real Nutstore account. It is skipped when no
credentials are passed and it cleans up its remote test artifacts:

```shell
./gradlew :app:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.class=com.beemdevelopment.aegis.backup.NutstoreLiveIntegrationTest" \
  "-Pandroid.testInstrumentationRunnerArguments.nutstoreAccount=<account>" \
  "-Pandroid.testInstrumentationRunnerArguments.nutstorePassword=<application password>"
```

## License and attribution

This project is licensed under the **GNU General Public License v3.0**, the same license as the
upstream project it is based on; see [LICENSE](LICENSE).

- Upstream project: [beemdevelopment/Aegis](https://github.com/beemdevelopment/Aegis),
  Copyright (C) the Aegis contributors, GPL-3.0.
- This fork: modifications made in October 2026 to add Nutstore (Jianguoyun) backup support.
  A summary of the changes is in [CHANGELOG.md](CHANGELOG.md); the full corresponding source
  is included in this repository.

The app name "Aegis" and the original icon come from the upstream project and are kept so that
existing users recognize the base app. This repository is an unofficial fork and is not
affiliated with the upstream project or with Nutstore.

## Documentation

- [FAQ.md](FAQ.md) – upstream frequently asked questions
- [docs/vault.md](docs/vault.md) – upstream security design of the vault format
- [CONTRIBUTING.md](CONTRIBUTING.md) – upstream contribution guidelines
- [SECURITY.md](SECURITY.md) – how to report security issues
