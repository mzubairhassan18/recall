# Recall — build and verification report

Date: 2026-10-26 (initial report 2026-10-25; follow-up runtime notes below)
Workspace: `D:\recalla`
Node: `v24.22.0` · pnpm: `10.17.1` · JDK: `21.0.12` · Gradle: `8.14.6` · AGP: `8.13.2` · Kotlin: `2.3.21`
Android: compile/target SDK `36`, minSdk `26`, build-tools `36.0.0`

This report records what was actually executed. Items that could not be exercised in this
environment are listed as **not verified**, not silently marked as passing.

## Acceptance matrix

| Requirement | Status | Evidence |
| --- | --- | --- |
| pnpm monorepo with shared types + crypto/search cores | PASS | `packages/shared-types`, `packages/crypto-core`, `packages/search-core`, `pnpm-workspace.yaml` |
| PBKDF2-SHA256 600k, 16-byte salt, AES-256-GCM, 12-byte IV, `{v,iv,ct}` envelope | PASS | 34 crypto tests, 32 cross-language parity checks |
| TypeScript ↔ Kotlin byte parity | PASS | `pnpm parity`: PBKDF2 (incl. Unicode), GCM NIST/OpenSSL, envelope fixtures, random envelopes, sync username, fingerprint |
| Fixed 7-step search pipeline; BM25; synonyms; 180-day recency; fuzzy fallback; never empty | PASS | 36 search tests; `pnpm fixtures:verify` 20 cases + 5 edge queries; Android `:core:test` 20 fixtures |
| Relay stores only ciphertext + `{id,user_id,created_at,size}`; no search/OCR/plaintext/push | PASS | `pnpm relay:smoke`: health, auth, register, PUT/GET/list/since, decrypt, per-user scoping, validation |
| Relay remote deploy | PASS | `https://recall-relay.mzubairhassan18.workers.dev`; D1 `recall-meta`, R2 `recall-blobs`; health 200, unauth 401, remote register/PUT/GET/list/decrypt |
| Local DB unreadable without passphrase | PASS (code/tests) | Chrome/desktop tests round-trip encrypted persistence; Android uses SQLCipher; no plaintext DB file on disk |
| Chrome extension | PASS (build/tests/static + mocked popup) | 35 tests, typecheck, `dist/` built; tags/favorites/archive and popup interactions verified with a mocked Chrome API harness; manual unpacked-extension reload pending |
| Desktop app | PASS (build/tests/smoke) | 36 tests, typecheck, `dist/` built, `--smoke` exit 0 |
| Signed Android release APK | PASS (build/static + partial runtime) | `:app:assembleRelease`; v2 signature verifies; installed on SM-A715F; desktop↔Android sync verified; biometric enrollment runtime pending |
| No server-side search/OCR/LLM/embeddings; no web/iOS | PASS | Relay routes only auth/blob/list/health; no model or index service exists |
| No cloud calls before step 4 | PASS | Steps 1–3 used local Miniflare/D1/R2; the remote deploy ran only after explicit confirmation |
| Never log secrets/plaintext | PASS | Scripts read secrets from env/prompt only; parity/backup print counts, not keys or payloads |

## Commands and results

### TypeScript workspace

```text
pnpm test
  @recall/shared-types   1 passed
  @recall/crypto-core   34 passed
  @recall/search-core   36 passed
  @recall/chrome-ext    35 passed
  @recall/desktop       36 passed
  @recall/relay         relay-smoke: PASS
                        (health, auth, register, put x3, list, since, get, decrypt,
                         scoping, validation)
```

```text
pnpm typecheck
  shared-types, relay, crypto-core, search-core, chrome-ext, desktop: 6/6 clean
```

```text
pnpm fixtures:verify
  20 cases verified (case=2 exact=5 fallback=4 number=1 paraphrase=1 recency=2
                     screenshot=1 sparse=1 synonym=4 typo=1 url=1)
  never-empty fallback verified for 5 edge queries
```

```text
pnpm parity
  PBKDF2 fixtures: 3 ok
  PBKDF2 unicode passphrase: 1 ok
  sync username: 3 ok
  key fingerprint: 1 ok
  AES-256-GCM fixtures: 4 ok
  envelope round-trip fixtures: 10 ok
  randomized envelopes: 10 ok
  PASS: TypeScript and Kotlin crypto agree on 32 checks.
```

```text
pnpm build
  apps/chrome-ext/dist  built
  apps/desktop/dist     built
```

Desktop smoke (GPU disabled to avoid a headless GPU warning):

```text
cd apps/desktop
electron.exe . --smoke --disable-gpu
[smoke] ok store=1 results=1 bridge=true renderer=true
exit=0
```

### Android

```text
cd apps/android
$env:GRADLE_USER_HOME="D:\gcache"
./gradlew.bat :core:test
  CryptoTest: 6 passed (PBKDF2, GCM, envelope, canonical JSON, tamper, sync/fingerprint)
  SearchTest: 5 passed (fixtures, normalization, synonyms, snippets, never-empty)
  BUILD SUCCESSFUL
```

```text
./gradlew.bat :parity:installDist
  BUILD SUCCESSFUL
```

```text
./gradlew.bat :app:assembleRelease
  BUILD SUCCESSFUL
  warning: unable to strip libandroidx.graphics.path.so, libmlkit_google_ocr_pipeline.so,
           libsqlcipher.so (packaged as-is)
```

APK inspection:

```text
package: com.recall.app  versionCode=1  versionName=1.0.0
minSdk=26  targetSdk=36  compileSdk=36
native-code: arm64-v8a, x86_64
APK Signature Scheme v2: true (v1/v3/v3.1/v4: false)
Signer CN=Recall, OU=Recall, O=Recall, L=Local, ST=Local, C=US
Signer SHA-256: a4115e93877001a6eb7363c6fb0f543a2ad9c8a3422cdd7da48aa40d785e4faa
Size: 47,070,345 bytes
SHA-256: 47bccc88f573dc43d56afeaad64781803bf3ad3f81e1aa38f6fae3729c674a33
```

Artifacts:

```text
apps/android/build/Recall-1.0.0-release.apk
apps/android/build/recall-release.keystore   (local convenience copy, gitignored)
apps/android/build/SHA256SUMS.txt
apps/android/app/build/outputs/apk/release/app-release.apk
```

The signing key was generated locally (RSA 4096, alias `recall`, 10,000-day validity). The
password is stored only in the gitignored `apps/android/keystore.properties`; it is not in this
report or any committed file. `apps/android/keystore.properties.example` shows the shape.

## Deviations and decisions

1. **SQLite-WASM instead of sql.js / better-sqlite3.** The Chrome extension and desktop app use
   `@sqlite.org/sqlite-wasm` (3.54.1-build1). `sql.js`'s shipped build has no FTS5; native
   `better-sqlite3` would require per-ABI rebuilds. The store/schema/search behavior is otherwise
   the same as the spec.
2. **Desktop persistence shape.** SQLite runs in WASM memory and is exported as
   `recall.db.enc` (`iv || ciphertext || tag`) in Electron `userData`. There is no plaintext
   `.sqlite` file on disk; the FTS5 index is inside the encrypted container.
3. **Android SQLCipher + manual FTS5.** Room only exposes FTS4 annotations, so the `captures_fts`
   FTS5 virtual table and its external-content triggers are created with
   `SupportSQLiteDatabase`. SQLCipher 4.6.1's bundled SQLite was verified to have
   `ENABLE_FTS5=True`. The whole database, including the index, is encrypted by SQLCipher.
4. **Android release minification is off.** Room, SQLCipher, ML Kit, and WorkManager ship
   consumer rules, but an obfuscated v1 failure is harder to diagnose than a larger APK.
   `isMinifyEnabled=false`, `isShrinkResources=false`.
5. **Android runtime coverage is partial.** The release APK is built, signed, and installed on a
   Samsung SM-A715F (Android 13 / SDK 33). Desktop↔Android encrypted sync, pull-to-refresh,
   clipboard/share capture, and source-URL metadata were exercised on that device. The biometric
   enrollment prompt was changed to use a `BiometricPrompt` `CryptoObject`; that specific flow,
   MediaStore observer, WorkManager, widget, ML Kit OCR, and broader Compose UI coverage still
   need a runtime pass after the user unlocks the phone.
6. **Biometric unlock keeps the passphrase in memory only while unlocked.** The optional
   biometric vault stores the passphrase encrypted by an Android Keystore AES key that requires
   `BIOMETRIC_STRONG` and is invalidated by new biometric enrollment. It is never written to
   plaintext preferences. The in-memory copy is needed to re-key SQLCipher after unlock.
7. **Relay-secret storage fallbacks.** Android wraps the relay secret with the Keystore when
   available; otherwise it stores base64 (documented, not encryption). Desktop uses Electron
   `safeStorage` when available and a base64 fallback otherwise. The relay secret is never
   logged.
8. **Sync identity uses PBKDF2, not a plain SHA-256.** `u_` +
   `hex(PBKDF2-HMAC-SHA256(passphrase, "recall-sync-v1", 600000, 16))`. This prevents the relay's
   user table from becoming a fast offline passphrase verifier. Documented in
   `docs/wire-format.md` and covered by parity tests.
9. **Polling sync, not push.** The relay has no push/WebSocket endpoint by design. Chrome and
   desktop poll every 30 s while unlocked; Android uses a 15-minute periodic WorkManager job that
   no-ops when the vault is locked (the key is not persisted).
10. **APK signature is v2-only.** minSdk 26 accepts v2; v1 is unnecessary for this range. v3/v4
    are not emitted by the current AGP signing config.
11. **Local-only is a one-way setup choice in the UI.** A vault created without a relay has no
    "add relay later" flow in v1; it can still export/import encrypted backups. Re-running setup
    would create a new local database, so the UI does not expose it.
12. **Backup CLI zip writer.** `scripts/export-backup.mjs` writes store-only ZIP entries; the
    reader handles both stored and deflated entries, so it reads the app's exports. The demo
    manifest includes an optional public `salt_b64` for offline verification; normal app exports
    do not include a salt.
13. **Screenshot allowlist uses usage-access stats.** No AccessibilityService is requested.
    Proposals are off by default; "ask", "hold 60 minutes", and "allowlist" are explicit modes.
14. **No web app, no iOS, no server-side search/OCR, no LLM/embeddings.** These are out of scope
    and no code path implements them.

## Relay deployment (complete)

Deployed on 2026-10-25 using the existing Wrangler OAuth session for the account that also owns
`cortex`:

```text
Worker:      recall-relay
URL:         https://recall-relay.mzubairhassan18.workers.dev
Version ID:  5e06334d-56cc-48a6-be6b-b245155d536e
D1:          recall-meta  (bc653abe-09e5-4b4f-825f-b28f89ca6421, EEUR)
R2:          recall-blobs
Secret:      RELAY_SECRET (Worker secret; stored only in the gitignored local .env.local)
Preview:     disabled (`preview_urls = false`)
```

Remote verification:

```text
GET  /health                    -> 200 {"ok":true}
GET  /list?since=0 (no auth)    -> 401
D1 schema                       -> users, blobs, idx_blobs_user_created
POST /auth/register (secret)    -> 200
GET  /list (secret + user id)   -> 200 []
PUT  /blob/:id (envelope)       -> 204
GET  /blob/:id                  -> 200, decrypts to the expected payload
GET  /list?since=0              -> 200, contains the uploaded id
```

The remote smoke used a temporary user and blob; both were deleted from D1/R2 afterwards. No
plaintext, passphrase, or secret was printed.

The checked-in `apps/relay/wrangler.toml` now contains the real D1 id. Reproducing the deploy on
a fresh account:

```bash
cd apps/relay
pnpm exec wrangler login
pnpm exec wrangler d1 create recall-meta
# replace database_id in apps/relay/wrangler.toml with the returned id
pnpm exec wrangler r2 bucket create recall-blobs
pnpm exec wrangler d1 migrations apply recall-meta --remote
pnpm exec wrangler secret put RELAY_SECRET
pnpm exec wrangler deploy
```

Set `RELAY_URL` to the printed Worker origin in the client setup screen. Do not put
`RELAY_SECRET` in any client `.env` that is committed; the clients ask for it at setup and store
it locally. The local `.env.local` is gitignored.

## Known gaps

- Android biometric enrollment/decryption after the `CryptoObject` fix (the APK is installed; the
  phone was locked at install time). Share/selection capture, screenshot notification actions,
  widget, ML Kit OCR, and battery/WorkManager behavior also need a broader device pass.
- Manual Chrome extension UI run after the popup updates (reload `apps/chrome-ext/dist` at
  `chrome://extensions`, then open the popup). The mocked Chrome API harness passed layout and
  interaction checks; context menus and 30 s sync still need a manual browser pass.
- Manual desktop tray/hotkey interaction. Desktop is built and unit-tested, and passes the
  headless smoke test.
- No automated instrumentation tests; Android UI coverage is compilation, static APK inspection,
  and the partial device checks listed above.

## Follow-up verification (2026-10-26)

- **Cross-device sync:** PASS. Desktop and Android share the same passphrase-derived identity;
  captures created on either side appeared on the other after sync/pull-to-refresh.
- **Android clipboard source metadata:** PASS for a synthetic Windows `CF_HTML` clipboard payload;
  the phone displayed the source app/browser and URL. Generic Android clipboard labels such as
  `data` are filtered.
- **Android biometric enrollment bug:** fixed. Auth-bound Keystore encryption cannot call
  `doFinal` without a prior `BiometricPrompt`; enrollment now prepares the cipher, authenticates
  with it as a `CryptoObject`, and only then stores the wrapped passphrase. The previous setup
  path reported "Biometric unlock could not be enabled."
- **Chrome popup hang:** fixed. Author CSS rules such as `.detail { display: grid; }` overrode the
  HTML `hidden` attribute, so the empty detail overlay covered the popup from boot. A global
  `[hidden] { display: none !important; }` rule was added and `dist/` rebuilt.
- **APK:** rebuilt and installed as `lastUpdateTime=2026-10-26 01:49:19`; v2 signature verifies
  with the same signer certificate. SHA-256 is recorded above.
- **Regression checks:** `pnpm typecheck` (6/6 projects), `pnpm test` (shared-types 1, crypto 34,
  search 36, Chrome 32, desktop 36, local relay smoke PASS), and `pnpm build` all pass after the
  fixes. A headless 430x760 render of `dist/popup.html` showed the header only, with no visible
  detail overlay; extension APIs are absent in a `file://` render, so the full popup still needs
  the manual `chrome://extensions` reload.