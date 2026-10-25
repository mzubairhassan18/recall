# Wire format

This file is normative. TypeScript (`packages/crypto-core`) and Kotlin
(`apps/android/app/src/main/java/com/recall/app/crypto/CryptoCore.kt`) must produce
byte-identical outputs for the same inputs. `scripts/crypto-parity.mjs` enforces it.

## KDF

| Field | Value |
| --- | --- |
| Algorithm | PBKDF2-HMAC-SHA256 |
| Iterations | 600 000 |
| Salt | 16 bytes, per user, generated once on first device setup |
| Derived key | 32 bytes |
| Salt storage | Base64 in D1, plaintext (it is not a secret) |

## Cipher

| Field | Value |
| --- | --- |
| Algorithm | AES-256-GCM |
| IV | 12 random bytes, fresh for every capture |
| Tag | 16 bytes, appended to ciphertext |
| AAD | none |

## Envelope

JSON, exactly these keys, no extra keys:

```json
{ "v": 1, "iv": "<base64 of 12 bytes>", "ct": "<base64 of ciphertext || 16-byte tag>" }
```

Base64 is standard alphabet with `=` padding (RFC 4648 §4), no line breaks.

## Plaintext payload

UTF-8 JSON inside `ct`:

```json
{
  "type": "url | text | screenshot",
  "url": "optional",
  "title": "optional",
  "text": "optional",
  "ocr_text": "optional",
  "thumbnail_b64": "optional, base64 PNG/JPEG, <= 256px longest edge"
}
```

Field presence rules:

- `type: "url"` — `url` required, `title` optional.
- `type: "text"` — `text` required, `url`/`title` optional (source context).
- `type: "screenshot"` — `ocr_text` required (may be empty string), `thumbnail_b64` required,
  `title` optional (first OCR line).

`thumbnail_b64` is a *payload* field, so it is inside the ciphertext. Thumbnails are the only
image bytes that ever leave a device; full-resolution screenshots are discarded after OCR.

## Determinism rules

- JSON for fixtures is serialized with keys in the order `type, url, title, text, ocr_text,
  thumbnail_b64` and no insignificant whitespace. The app may use any serializer on input, but
  parity fixtures use this canonical order.
- `iv` may be injected for tests; production always generates 12 random bytes.
- The KDF output must be identical in JVM and WebCrypto (both are PBKDF2-HMAC-SHA256).
- Envelope `ct` is the raw GCM output concatenated with the tag, then Base64. Do not Base64
  the ciphertext and tag separately.

## Sync identity

The relay needs a stable username so two devices with the same passphrase join the same
stream without pairing. Clients derive it locally:

```
username = "u_" + hex(PBKDF2-HMAC-SHA256(passphrase, "recall-sync-v1", 600000, 16))
```

`"recall-sync-v1"` is a fixed, public 14-byte salt. The handle is a lookup key, not a secret.
PBKDF2 is used (rather than SHA-256) so the relay's user table cannot be used as a fast
offline passphrase verifier. TypeScript (`deriveSyncUsername` in `crypto-core`) and Kotlin
must agree byte-for-byte; `scripts/crypto-parity.mjs` covers it.

`POST /auth/register` is idempotent by username and returns the per-user 16-byte salt. A second
device registering the same handle receives the same `user_id` and salt, then derives the same
AES key.

## Conservative interpretation notes

- NIST does not publish PBKDF2-HMAC-SHA256 vectors at 600 000 iterations. `pbkdf2.json` pins
  two published NIST/RFC-adjacent vectors at their published iteration counts plus a 600 000
  iteration vector generated with OpenSSL's reference implementation and frozen. The high-cost
  vector guards the production parameters; the published vectors guard the algorithm.
- GCM vectors are the NIST/McGrew–Viega AES-256 cases 13–15 (zero key/IV/empty, zero
  key/IV/one block, non-zero key/IV with AAD), independently re-verified with OpenSSL.
