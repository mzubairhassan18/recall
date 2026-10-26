# Recall

**Your memory, encrypted.**

Recall is a private capture layer for the things you want to remember: a thought, a link, a
selection, a screenshot. Capture it in one gesture, search it later, and sync it without giving a
cloud provider the keys.

[**Download**](https://github.com/mzubairhassan18/recall/releases/latest) ·
[**Landing page**](https://mzubairhassan18.github.io/recall/) ·
[**Design system**](docs/design-system.md)

---

## Download

| Platform | Asset | Notes |
| --- | --- | --- |
| Android | [`Recall-1.0.0.apk`](https://github.com/mzubairhassan18/recall/releases/latest/download/Recall-1.0.0.apk) | Signed release, Android 8.0+, `arm64-v8a` + `x86_64` |
| Chrome / Edge | [`recall-chrome-1.0.0.zip`](https://github.com/mzubairhassan18/recall/releases/latest/download/recall-chrome-1.0.0.zip) | Load unpacked in Developer mode |
| Windows | [`Recall-1.0.0-win-x64.zip`](https://github.com/mzubairhassan18/recall/releases/latest/download/Recall-1.0.0-win-x64.zip) | Portable x64 build; unzip and run `Recall.exe` |
| Checksums | [`SHA256SUMS.txt`](https://github.com/mzubairhassan18/recall/releases/latest/download/SHA256SUMS.txt) | Verify every download |

### Android

1. Download the APK on your phone.
2. Allow installs from your browser or file manager when prompted.
3. Open Recall and create a data passphrase. It is the encryption key, so use 4–6 diceware words
   and keep it safe.
4. Fingerprint unlock is offered first. A 4-digit PIN is the fallback local gate.

There is no password recovery. If you lose the passphrase, the data is gone by design.

### Chrome / Edge

1. Unzip `recall-chrome-1.0.0.zip`.
2. Open `chrome://extensions` (or `edge://extensions`) and turn on **Developer mode**.
3. Choose **Load unpacked** and select the unzipped folder.
4. Pin Recall, then enter the same data passphrase you use on your other devices.

Select text or right-click a page and choose **Save to Recall**. The extension stores an encrypted
local database and syncs ciphertext through the relay.

### Windows

1. Unzip `Recall-1.0.0-win-x64.zip`.
2. Run `Recall.exe`. The build is portable; there is no installer.
3. Enter your data passphrase. Use `Ctrl+Shift+R` to capture the clipboard and `Ctrl+Shift+S` to
   focus search.

The portable binary is not code-signed, so Windows may show a SmartScreen warning. The SHA-256
checksums are in `SHA256SUMS.txt`.

---

## What it does

- **One-gesture capture** from Android share/selection, Chrome context menus, and a desktop global
  hotkey.
- **Local-first search** over titles, snippets, URLs, notes, and OCR text. Results are instant and
  work offline.
- **Encrypted sync** across devices. The relay stores ciphertext and minimal metadata; it cannot
  search, index, OCR, or decrypt.
- **A calm interface**: deep ink, aurora light, generous spacing, and one obvious action at a time.
- **The same data model everywhere**: SQLCipher + FTS5 on Android; SQLite-WASM + FTS5 inside an
  encrypted container on Chrome and desktop.

## How privacy works

The passphrase is the only encryption secret. Recall derives a 32-byte AES-256-GCM key with
PBKDF2-HMAC-SHA256 (600,000 iterations, per-user 16-byte salt). Every capture gets a fresh 12-byte
IV and is encrypted on-device before it leaves the app.

The relay sees only:

```text
{ id, user_id, created_at, size, ciphertext, salt }
```

The sync identity is derived from the passphrase, so any device with the same passphrase joins the
same stream without pairing. The shared relay secret compiled into the clients is a gate, not the
encryption key; it is extractable from any client and should be rotated if a build leaks. The
passphrase remains the security boundary.

Android adds a local 4-digit PIN and biometric unlock. The PIN is a convenience gate; the passphrase
is wrapped by a hardware-backed Keystore key, and attempts are rate-limited. Fingerprint unlock is
enabled by default when strong biometrics are available.

Read the full model in [`docs/wire-format.md`](docs/wire-format.md) and
[`docs/api.md`](docs/api.md).

## The mark

The Recall mark is a bare, Geist-grade **R** used as a clip. A true wave fills it: a
violet/blue/cyan/pink tide rises from the bottom, a pink wash crosses from the right, and both
surfaces shiver. Static icons use the same R with a fixed wave frame. The canonical SVG is
[`brand/recall-mark.svg`](brand/recall-mark.svg); the system is documented in
[`docs/design-system.md`](docs/design-system.md).

## Repository

| Path | What it is |
| --- | --- |
| `apps/android` | Kotlin/Compose app, SQLCipher + FTS5, ML Kit OCR, biometric unlock |
| `apps/chrome-ext` | MV3 extension, context menus, SQLite-WASM + FTS5, encrypted IndexedDB |
| `apps/desktop` | Electron tray app, global shortcuts, same encrypted SQLite-WASM store |
| `apps/relay` | Cloudflare Worker + D1 + R2, dumb ciphertext relay |
| `packages/crypto-core` | PBKDF2, AES-256-GCM envelopes, sync identity |
| `packages/search-core` | The fixed 7-step search pipeline |
| `packages/shared-types` | Cross-package interfaces |
| `docs` | Wire format, API, data model, search spec, fixtures, design system |
| `brand` | Canonical mark, motion study, social card source |

## Development

Requirements: Node.js 20.11+, pnpm 10, and (for Android) JDK 21 plus Android SDK 36.

```bash
pnpm install
pnpm relay:defaults       # compile local relay URL/secret into the clients
pnpm typecheck
pnpm test
pnpm build
```

The automated suite stays offline. `pnpm relay:defaults` reads the gitignored `.env.local`; never
commit the generated relay-default files or the shared secret.

### Chrome extension

```bash
pnpm --filter @recall/chrome-ext build
# chrome://extensions -> Developer mode -> Load unpacked -> apps/chrome-ext/dist
```

### Desktop

```bash
pnpm --filter @recall/desktop start
# or
pnpm --filter @recall/desktop build
```

### Android

```bash
cd apps/android
./gradlew.bat :app:assembleRelease
```

The signed APK is written to `apps/android/app/build/outputs/apk/release/app-release.apk`.
Signing values live in the gitignored `apps/android/keystore.properties`.

### Verification

| Command | What it proves |
| --- | --- |
| `pnpm test` | Shared types, crypto, search, Chrome, desktop, and local relay tests |
| `pnpm typecheck` | All TypeScript projects |
| `pnpm fixtures:verify` | 20 search cases + 5 never-empty edge queries |
| `pnpm parity` | PBKDF2/GCM/envelope/sync-identity/fingerprint TS ↔ Kotlin parity |
| `pnpm relay:smoke` | Health, auth, register, put/get/list/since, decrypt, per-user scoping |
| `pnpm export:backup --demo --out recall-demo.zip` | Encrypted zip export format |
| `pnpm export:backup --input recall-demo.zip --verify` | Decrypts every envelope without printing plaintext |

Android JVM tests:

```bash
cd apps/android
./gradlew.bat :core:test
```

## License

MIT. See [`LICENSE`](LICENSE).
