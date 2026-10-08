# Security policy

This repository is an unofficial fork of
[Aegis Authenticator](https://github.com/beemdevelopment/Aegis). It contains all upstream code
plus the changes listed in [CHANGELOG.md](CHANGELOG.md).

## Reporting a vulnerability

For issues in the fork-specific code (everything under
`app/src/main/java/com/beemdevelopment/aegis/backup/`, the Nutstore settings screen
`NutstoreBackupsActivity`, and the WebDAV/credential handling), please use GitHub's private
vulnerability reporting: **Security → Report a vulnerability** in this repository. Please do
not open a public issue for security problems.

For vulnerabilities in Aegis itself (vault format, encryption, OTP handling, import/export,
unlocking), please report them to the upstream project instead:
https://github.com/beemdevelopment/Aegis

## Please never include

- Nutstore account passwords or application passwords
- vault passwords or backup passwords
- exported vault files or screenshots of QR secrets

When in doubt, describe the problem and the steps to reproduce it without the secrets.
