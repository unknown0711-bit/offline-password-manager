# Offline Vault — Phase 6

This repository is the command-line Android scaffold for a fully offline,
SQLCipher-backed password manager and media vault. It contains the secure
key-management foundation, reboot-safe lockout guard, Room EAV database schema,
terminal login/autofill feature, first-launch setup wizard, and normal lock
screen. The only supported device workflow uses Gradle and a connected physical
Android device; Android Studio and emulators are intentionally out of scope.

## Requirements

- Linux or macOS shell
- JDK 17
- Android SDK with platform 35 and build-tools 35.x
- A connected physical Android device for install/smoke testing

The Gradle wrapper is committed, Java is pinned in `.java-version`, dependency
versions are locked in `app/gradle.lockfile`, and no global Gradle installation
is required. The repository also contains a Dockerfile that installs the JDK,
Android command-line SDK, and project dependencies for a clean Linux build.

## Setup

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew --version
```

If the SDK is not already present, install platform 35 and build-tools 35.x
with the Android command-line SDK tools. Do not use Android Studio or an
emulator for this project.

## Build, run, test, and deploy

The single project check is:

```sh
make test
```

`make test` runs the complete JVM test suite, Android lint, and the debug APK
build with `--offline`. The pinned dependencies must already be available in
the local Gradle cache. A clean machine can populate that cache once with the
same Gradle task without `--offline`; subsequent checks use the offline command.

For a local debug build:

```sh
./gradlew assembleDebug
```

Install and launch on a connected physical device:

```sh
make install
adb shell am start -n com.example.offlinevault/.ui.MainActivity
```

The supported deployment target is a physical device with a StrongBox-capable
biometric sensor. The first launch shows setup; later launches require the
master password once after reboot before biometric unlock is permitted.

To reproduce the build in Docker:

```sh
docker build -t offline-vault .
docker run --rm offline-vault
```

The container runs `make test` as its default command. Device installation is
performed from the host with `adb`; no emulator or Android Studio steps are
part of the supported workflow.

For an installable debug APK without `make install`:

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

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

## Phase 1 security integration

`VaultKeyManager.provision()` is the complete key-provisioning orchestration,
but the future UI must pass a cipher authenticated by `BiometricPrompt` to the
method; the key wrapper does not bypass biometric authentication. After an
unlock, derive the SQLCipher passphrase with `SqlCipherKeyDeriver`, open the
database with `VaultDatabaseFactory`, and close both the database and DEK when
the locked state is entered.

The production BIP39 asset is `app/src/main/assets/bip39_english.txt`.

## Phase 6 setup and session flow

- On first launch, `SetupWizardScreen` collects a master password in secure
  fields, shows strength feedback, generates a 24-word BIP39 phrase, and asks
  for three randomly selected confirmation words.
- The Activity passes a StrongBox encryption `CryptoObject` through
  `BiometricPrompt` before `VaultSessionCoordinator.completeSetup()` persists
  the three independent wrapped DEK records.
- `WrappedVaultKeyStore` persists only wrapped ciphertext and the StrongBox key
  alias. The plaintext password, phrase, DEK, and SQLCipher material remain
  transient and are wiped at ownership boundaries.
- The lock screen uses the persistent `BruteForceGuard` countdown and supports
  master-password unlock plus Strong biometric unlock. Biometric unlock is
  gated by `MasterPasswordBootGate` until the master password has succeeded
  once during the current boot.
- A successful session opens SQLCipher, installs `LoginUiSession`, installs the
  unlocked autofill dependencies, and switches to `LoginListScreen`. Locking
  the coordinator closes the database, clears process-local providers, and
  wipes the in-memory DEK.

## Phase 2: Logins and Autofill

Phase 2 adds the login feature under `app/src/main/java/com/example/offlinevault/login/`
and terminal Compose screens under `app/src/main/java/com/example/offlinevault/ui/`.

- The login list decrypts only title, username, tags, and the presence of a TOTP
  field. Passwords and TOTP secrets are not read by the list projection.
- Detail secrets are held in `CharArray`/`ByteArray` wrappers, revealed on demand,
  masked after 20 seconds and on `ON_PAUSE`, and cleared when the view model is
  cleared. Secret values use JetBrains Mono; general UI uses the premium sans
  serif Material typography.
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

The launcher host is `com.example.offlinevault.ui.MainActivity`. It uses direct
state switching rather than an animated navigation host and routes between
setup, lock, and the Phase 2 login feature only after the session coordinator
has completed the corresponding security operation.

## Phase 3: Secure Media Vault

The Media Vault is available from the unlocked login list and from `SEND`
share intents. Imports are foreground WorkManager jobs and accept only
`content://` URIs. The worker reports byte progress and non-secret MIME/size
metadata while `MediaVaultRepository` streams the source through Tink's
`AES256_GCM_HKDF_4KB` `StreamingAead` into `filesDir/vault/<uuid>`; no original
name or extension is used on disk, and the source is never deleted implicitly.

Media metadata is encoded into one deterministic payload and encrypted into
the existing Room EAV `entry_fields` row using the exact AAD binding
`entry_fields|<entryUuid>|media_metadata|1`. File streams use a separate
purpose-bound AAD value `offline-vault/media/<entryUuid>/v1`. Successful imports
are decrypted and SHA-256 verified before the metadata transaction is written.

The streaming keyset is stored only as a Tink `EncryptedKeyset`, wrapped by the
unlocked DEK and then protected by the existing `EncryptedSharedPreferences`.
The process-local media session, thumbnail LRU, and PDF temp-file registry are
cleared before the vault DEK is released on lock.

- Image thumbnails use a bounded in-memory `LruCache`; there is no Coil disk
  cache and no thumbnail file.
- Video and audio use a Media3 ExoPlayer `DataSource` backed by Tink's
  seekable decrypting channel, so seeking never creates a plaintext staging
  file.
- PDFs are decrypted only to a randomly named file under
  `noBackupFilesDir/tmp/`, rendered through `PdfRenderer`, and deleted when the
  viewer closes or the session locks.
- After verified encryption, the user sees the explicit disclaimer
  `DELETES VIA SYSTEM. FLASH STORAGE MAY RETAIN FRAGMENTS.` before a
  `MediaStore.createDeleteRequest()` or SAF deletion is launched.
