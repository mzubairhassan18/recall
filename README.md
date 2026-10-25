# Recall

Capture anything in one gesture. Encrypt it on-device. Find it everywhere, offline-first.

> **Agent operating rules — read before touching any package**
>
> 1. **Spec before code.** Read `docs/*.md` for the package you are changing. If a spec is ambiguous, take the conservative interpretation and note it in the PR/report. Never invent behavior.
> 2. **Fixtures first.** Wire the fixtures in `docs/test-vectors/`, then implement until they pass, then move on.
> 3. **One package per task.** Never edit two apps in one task. Cross-package changes update the shared package first, then each consumer in a follow-up.
> 4. **Stubs, then implementations.** `shared-types` defines every interface up front. `crypto-core` and `search-core` are implemented and tested standalone before any app imports them.
> 5. **Crypto parity is enforced.** `pnpm parity` must produce byte-identical envelopes for TS and Kotlin given a fixed salt + IV. If they diverge, fix Kotlin — not the spec.
> 6. **Every app is testable offline.** Sync clients honour `LOCAL_ONLY=1`; no network calls are made in that mode.
> 7. **No cloud calls until step 4.** Steps 1–3 run fully offline. At step 4 the agent prints the exact wrangler commands and env-var names it needs, then waits for confirmation.
> 8. **Never log or echo secrets.** No `RELAY_SECRET`, passphrase, or decrypted payload in logs, fixtures, screenshots, or commits. Test secrets are generated at test time.

## Packages

| Path | What it is | Deps |
| --- | --- | --- |
| `packages/shared-types` | All cross-package interfaces. No logic. | — |
| `packages/crypto-core` | PBKDF2-SHA256 (600k) → AES-256-GCM envelopes, sync identity, verifier. | shared-types |
| `packages/search-core` | 7-step search pipeline over an injected SQLite adapter. | shared-types |
| `apps/relay` | Cloudflare Worker + R2 + D1. Dumb ciphertext relay. | shared-types |
| `apps/chrome-ext` | MV3, context menus, `@sqlite.org/sqlite-wasm` FTS5, encrypted IndexedDB, 30 s poll sync. | crypto-core, search-core, shared-types |
| `apps/desktop` | Electron tray, global hotkeys, same sqlite-wasm FTS5 store, encrypted `recall.db.enc`, 30 s poll sync. | crypto-core, search-core, shared-types |
| `apps/android` | Kotlin/Compose, SQLCipher + FTS5 via Room, ML Kit OCR, share/selection capture, screenshot proposals, widget, biometric unlock. | shared-types (ports of crypto/search) |
| `scripts/*` | Crypto parity, fixture verification, relay smoke, encrypted backup. | — |

The Chrome extension and desktop app intentionally share the same SQLite-WASM/FTS5 store
implementation. SQLite-WASM is used instead of `sql.js` (no FTS5 in its shipped build) and
instead of `better-sqlite3` (native ABI builds). See `docs/build-report.md`.

## Quick start (offline, no cloud)

```bash
pnpm install
pnpm relay:defaults       # compile RELAY_URL/RELAY_SECRET from .env.local into the clients
pnpm test                 # all package tests + local relay smoke
pnpm typecheck
pnpm fixtures:verify      # search fixtures against the TS reference adapter
pnpm parity               # TS ↔ Kotlin PBKDF2/GCM/envelope parity
pnpm relay:smoke          # local Worker, D1, R2; no Cloudflare account
```

The automated tests stay offline. The shipped clients use the compiled relay defaults, so setup
does not ask for a relay URL or shared secret.

### Client setup

- **Desktop / Chrome:** enter the data passphrase once. The same passphrase on another device joins
  the same encrypted sync stream.
- **Android:** enter the data passphrase once, choose a 4-digit daily PIN, and leave fingerprint
  unlock on. Confirm the system biometric prompt after setup. If it is skipped, enable it later
  under **Settings → Security → Biometric unlock**. The PIN is a local gate; the passphrase remains
  the encryption key and is wrapped by an auth-bound Android Keystore key. Pull down on the capture
  grid to sync.

The shared `RELAY_SECRET` is compiled into the desktop bundle, extension and APK for one-step setup.
It is therefore extractable by anyone who has a client. It is a relay gate, not the encryption key;
rotate it if a build leaks. The generated `relay-defaults` files are gitignored.

### Chrome / Edge

```bash
pnpm --filter @recall/chrome-ext build
# chrome://extensions -> Developer mode -> Load unpacked -> apps/chrome-ext/dist
```

After rebuilding, click **Reload** on the Recall card at `chrome://extensions`, then close and
reopen the popup. Loading `apps/chrome-ext/src` or `public` instead of `dist` produces the
"Could not load background script" error.

### Desktop

```bash
pnpm --filter @recall/desktop start
# or, after `pnpm --filter @recall/desktop build`:
cd apps/desktop
./node_modules/electron/dist/electron.exe . --disable-gpu
```

Headless smoke test (prints `store=1 results=1 bridge=true renderer=true`):

```bash
cd apps/desktop
./node_modules/electron/dist/electron.exe . --smoke --disable-gpu
```

### Android (signed release APK)

Requirements: JDK 21, Android SDK 36 / build-tools 36.0.0, and a local signing config.
The wrapper is committed; this workspace uses the cached Gradle distribution in `D:\gcache`.

```bash
cd apps/android
$env:GRADLE_USER_HOME="D:\gcache"
./gradlew.bat :core:test :parity:installDist
./gradlew.bat :app:assembleRelease
```

The signed artifact is copied to:

```text
apps/android/build/Recall-1.0.0-release.apk
apps/android/build/recall-release.keystore   # local convenience copy; never commit
apps/android/build/SHA256SUMS.txt
```

Current APK: package `com.recall.app`, versionCode `1`, versionName `1.0.0`, minSdk `26`,
targetSdk `36`, ABIs `arm64-v8a` + `x86_64`, APK Signature Scheme v2.
SHA-256: `47bccc88f573dc43d56afeaad64781803bf3ad3f81e1aa38f6fae3729c674a33`.

Install on a connected device:

```bash
adb install -r apps/android/build/Recall-1.0.0-release.apk
```

Wireless ADB can be very slow on some phones. If `adb install` stalls, push once and install from
the device instead:

```bash
adb push apps/android/build/Recall-1.0.0-release.apk /data/local/tmp/recall.apk
adb shell pm install -r --user 0 /data/local/tmp/recall.apk
```

Signing values live in `apps/android/keystore.properties` (gitignored). If the file is absent,
the release build is unsigned; copy `keystore.properties.example` values into place first.

## Verification scripts

| Command | What it proves |
| --- | --- |
| `pnpm test` | shared-types, crypto-core, search-core, Chrome, desktop tests; local relay smoke |
| `pnpm typecheck` | all six TypeScript projects |
| `pnpm fixtures:verify` | 20 search cases + 5 never-empty edge queries |
| `pnpm parity` | PBKDF2/GCM/envelope/sync-identity/fingerprint TS ↔ Kotlin byte parity |
| `pnpm relay:smoke` | health, auth, register, put/get/list/since, decrypt, per-user scoping, validation |
| `pnpm export:backup --demo --out recall-demo.zip` | encrypted zip export format |
| `pnpm export:backup --input recall-demo.zip --verify` | decrypts every envelope without printing plaintext |

The backup script reads secrets only from `RECALL_PASSPHRASE` / `RELAY_SECRET` (or
`--passphrase-file`), never from argv. The demo command requires `RECALL_PASSPHRASE`.

Android JVM tests (crypto vectors, all 20 search fixtures, snippet/normalization):

```bash
cd apps/android
$env:GRADLE_USER_HOME="D:\gcache"
./gradlew.bat :core:test
```

## Relay (deployed)

The relay is deployed as a single Worker with an R2 bucket (`recall-blobs`) and a D1 database
(`recall-meta`):

```text
https://recall-relay.mzubairhassan18.workers.dev
D1:  recall-meta (bc653abe-09e5-4b4f-825f-b28f89ca6421)
R2:  recall-blobs
```

The Worker secret is stored only in the gitignored local `.env.local` and was never printed.
`preview_urls` is disabled. Remote smoke checks passed for health, unauthorized rejection,
registration, blob PUT/GET, list, and decryption; the temporary test rows/objects were removed.

To reproduce on a fresh Cloudflare account:

```bash
cd apps/relay
pnpm exec wrangler login                 # or set CLOUDFLARE_ACCOUNT_ID / CLOUDFLARE_API_TOKEN
pnpm exec wrangler d1 create recall-meta
# copy the returned database_id into apps/relay/wrangler.toml
pnpm exec wrangler r2 bucket create recall-blobs
pnpm exec wrangler d1 migrations apply recall-meta --remote
pnpm exec wrangler secret put RELAY_SECRET
pnpm exec wrangler deploy
```

The deploy URL is public; `RELAY_SECRET` is not. Run `pnpm relay:defaults` after changing
`.env.local` to compile the URL and secret into the clients. The checked-in `wrangler.toml` contains
the real deployed D1 id; replace it only when deploying to a different account.

## Docs

- `docs/wire-format.md` — KDF/cipher/envelope and the PBKDF2-derived sync identity
- `docs/api.md` — relay surface (5 endpoints, no search/OCR/plaintext)
- `docs/data-model.md` — local schema on every client
- `docs/search-spec.md` — the fixed 7-step pipeline
- `docs/test-vectors/` — committed deterministic fixtures
- `docs/build-report.md` — verification report, acceptance matrix, deviations

## Security model (short version)

The passphrase is the only encryption secret. PBKDF2-HMAC-SHA256 with 600 000 iterations and a
per-user 16-byte salt derives a 32-byte AES-256-GCM key. Every capture is encrypted on-device with
a fresh 12-byte IV. The relay stores ciphertext plus `{id, user_id, created_at, size}` and a
plaintext salt; it cannot search, index, OCR, or decrypt. Losing the passphrase means losing the
data — there is no recovery flow, by design.

On Android the 4-digit PIN is a local convenience gate, not a second encryption key. The passphrase
is wrapped by a hardware-backed Keystore key and the PIN attempts are rate-limited. Fingerprint
unlock is enabled by default when strong biometrics are available.

Local databases are encrypted at rest: SQLCipher on Android, and AES-GCM-wrapped SQLite bytes
in the extension/desktop (`recall.db.enc`). FTS5 indexes live inside that encrypted container.

Sync identity is derived from the passphrase:

```text
u_ + hex(PBKDF2-HMAC-SHA256(passphrase, "recall-sync-v1", 600000, 16))
```

Any device with the same passphrase joins the same relay stream without pairing. The handle is
a keyed lookup, not a secret; the passphrase strength remains the security boundary (use 4–6
diceware words).

## Scope guardrails

No server-side search, OCR, LLM, embeddings, web app, or iOS app. The relay is a dumb encrypted
blob store. Screenshot proposals are off by default; the allowlist mode uses usage-access stats
(no AccessibilityService) and never uploads plaintext.
