# Offline Vault — Phase 1

This repository is the command-line Android scaffold for a fully offline,
SQLCipher-backed password manager and media vault. Phase 1 contains the secure
key-management interfaces, field encryption, reboot-safe lockout guard, and the
Room EAV database schema. It intentionally does not contain UI or ViewModels.

## Requirements

- Linux or macOS shell
- JDK 17
- Android SDK with platform 35 and build-tools 35.x
- A connected physical Android device for install/smoke testing

The Gradle wrapper is committed, Java is pinned in `.java-version`, and no
global Gradle installation is required.

## Setup

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew --version
```

If the SDK is not already present, install platform 35 and build-tools 35.x
with the Android command-line SDK tools. Do not use Android Studio or an
emulator for this project.

## Build and test

The single offline-capable project check is:

```sh
./gradlew test lintDebug assembleDebug --offline
```

The first invocation requires the pinned dependencies to already be available
in the local Gradle cache. A clean machine can populate that cache once using
the same command without `--offline`; subsequent checks are offline.

For an installable debug APK:

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No emulator or Android Studio steps are part of the supported workflow.

## Security invariants

- The manifest removes `INTERNET` and `ACCESS_NETWORK_STATE` from the merged
  manifest as a safety net. No network dependency or network code is included.
- SQLCipher uses `net.zetetic:sqlcipher-android`, never the deprecated
  `android-database-sqlcipher` artifact.
- The 32-byte DEK is wrapped independently by Argon2id, StrongBox biometric
  Keystore, and a 24-word BIP39 phrase. StrongBox is required; there is no
  software fallback.
- Field encryption uses AES-256-GCM with a fresh 12-byte nonce and exact AAD:
  `table|entryUuid|field|schemaVersion`.
- Sensitive material crosses APIs as `CharArray`/`ByteArray` and is cleared at
  ownership boundaries. The BIP39 standard library's UTF-8/PBKDF2 operations
  necessarily create short-lived JVM byte buffers; these are wiped after use.
- `BruteForceGuard` persists its failure count and the monotonic deadline as
  `elapsedRealtime + bootCount` in encrypted preferences. On a reboot it
  re-applies the full active tier rather than treating elapsed time as valid
  across boots.
- Room uses stable UUID String keys for entries and fields. Encrypted field
  payload, nonce, and schema version are stored as bytes/metadata in the EAV
  table.

## Phase 1 integration notes

`VaultKeyManager.provision()` is the complete key-provisioning orchestration,
but the future UI must pass a cipher authenticated by `BiometricPrompt` to the
method; the key wrapper does not bypass biometric authentication. After an
unlock, derive the SQLCipher passphrase with `SqlCipherKeyDeriver`, open the
database with `VaultDatabaseFactory`, and close both the database and DEK when
the locked state is entered.

The production BIP39 asset is `app/src/main/assets/bip39_english.txt`.

## Phase 2: Logins and Autofill

Phase 2 adds the login feature under `app/src/main/java/com/example/offlinevault/login/`
and terminal Compose screens under `app/src/main/java/com/example/offlinevault/ui/`.

- The login list decrypts only title, username, tags, and the presence of a TOTP
  field. Passwords and TOTP secrets are not read by the list projection.
- Detail secrets are held in `CharArray`/`ByteArray` wrappers, revealed on demand,
  masked after 20 seconds and on `ON_PAUSE`, and cleared when the view model is
  cleared. Revealed password characters render green letters, amber digits, and
  red symbols.
- Password history rotates newest-first through indexes `0..4` and encrypts each
  record with AES-GCM and exact AAD `history|<entryUuid>|<index>`.
- TOTP QR decoding uses the bundled `com.google.zxing:core:3.5.3` artifact.
  Camera frames are accepted in memory and never written to disk.
- Sensitive clipboard copies use API 33 `EXTRA_IS_SENSITIVE` and a durable
  WorkManager clear after 30 seconds. Call `LoginUiSession.lock()` from the
  Phase 1 lock transition to clear the clipboard immediately and remove the UI
  repository provider.
- `VaultAutofillService` uses RemoteViews layouts, a bundled JetBrains Mono font,
  and the bundled Mozilla Public Suffix List at
  `app/src/main/assets/autofill/public_suffix_list.dat`. Matching is by eTLD+1,
  not string suffixes. The service fails closed while locked.
- Install an `AutofillAuthenticationHandler` with
  `AutofillAuthentication.install(...)` to connect the Phase 1 unlock prompt to
  the locked autofill row. Install a `LoginUiSessionProvider` with
  `LoginUiSession.install(...)` after the unlocked SQLCipher database and DEK
  session are ready. Both process-local seams default to locked/null.

The launcher host is `com.example.offlinevault.ui.MainActivity`. It deliberately
shows a locked terminal screen until the Phase 1 session coordinator installs
the provider. It uses direct state switching rather than an animated navigation
host.
