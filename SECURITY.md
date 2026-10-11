# Credentials and release packaging

Public release distribution has resumed at the maintainer's request. The APK intentionally bundles the experimental accessory certificate and matching key described in docs/THIRD_PARTY_NOTICES.md. Anyone with the APK can extract them. Local compilation, Git history removal and obfuscation do not make a bundled shared key confidential or revoke previous copies.

The public Git tree and corresponding source archive exclude accessory keys and Android release-signing secrets. CI checks reject credential containers and private-key blocks in tracked files. Synthetic test identities are generated at runtime. The mobile module now bundles the two runtime identity files from the Git-ignored `.private/runtime-assets/offline-mfi/` directory by default, for both debug and release builds. `DIPLAY_AUTH_ASSETS_DIR` overrides that local directory. Missing or empty files fail the build; a new checkout or CI environment must provision them locally. Only the two expected runtime credential files are permitted. Android A's Bluetooth proxy module does not import them.

The Android APK-signing key is separate, stays local and is never bundled in the APK. Current acceptance of the experimental accessory identity does not establish Apple certification or guarantee future compatibility.

Review diagnostic reports before posting. Never include credentials or pairing records in public issues. Use GitHub private vulnerability reporting for sensitive findings.
