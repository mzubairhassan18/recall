import type { CapturePayload, Envelope } from "@recall/shared-types";
import { base64ToBytes, bytesToBase64, bytesToHex, randomBytes, utf8Decode, utf8Encode } from "./base64.js";
import { canonicalPayloadJson, isCapturePayload } from "./canonical.js";

export { bytesToBase64, base64ToBytes, bytesToHex, hexToBytes, utf8Encode, utf8Decode, randomBytes } from "./base64.js";
export { canonicalPayloadJson, isCapturePayload } from "./canonical.js";

/** Fixed production parameters — docs/wire-format.md. */
export const PBKDF2_ITERATIONS = 600_000;
export const SALT_BYTES = 16;
export const KEY_BYTES = 32;
export const IV_BYTES = 12;
export const GCM_TAG_BYTES = 16;
export const ENVELOPE_VERSION = 1 as const;

export type CryptoErrorCode =
  | "invalid-salt"
  | "invalid-passphrase"
  | "invalid-key"
  | "invalid-envelope"
  | "bad-payload"
  | "decrypt-failed";

export class CryptoError extends Error {
  readonly code: CryptoErrorCode;

  constructor(code: CryptoErrorCode) {
    super(code);
    this.name = "CryptoError";
    this.code = code;
  }
}

function subtle(): SubtleCrypto {
  const value = globalThis.crypto?.subtle;
  if (!value) throw new Error("webcrypto-unavailable");
  return value;
}

/**
 * TypeScript 5.7+ parameterises typed arrays with their backing buffer; WebCrypto's
 * BufferSource requires an ArrayBuffer-backed view. Every array we pass is one.
 */
function buf(value: Uint8Array): BufferSource {
  return value as unknown as BufferSource;
}

export function generateSalt(): Uint8Array {
  return randomBytes(SALT_BYTES);
}

export function generateIv(): Uint8Array {
  return randomBytes(IV_BYTES);
}

/** PBKDF2-HMAC-SHA256. Returns the raw derived key bytes (for parity tests and key import). */
export async function deriveKeyBytes(
  passphrase: string,
  salt: Uint8Array,
  iterations: number = PBKDF2_ITERATIONS,
  dklen: number = KEY_BYTES,
): Promise<Uint8Array> {
  if (typeof passphrase !== "string" || passphrase.length === 0) {
    throw new CryptoError("invalid-passphrase");
  }
  if (!(salt instanceof Uint8Array) || salt.length === 0) throw new CryptoError("invalid-salt");
  if (!Number.isInteger(iterations) || iterations < 1) throw new Error("invalid-iterations");
  if (!Number.isInteger(dklen) || dklen < 1) throw new Error("invalid-dklen");

  const baseKey = await subtle().importKey("raw", buf(utf8Encode(passphrase)), "PBKDF2", false, [
    "deriveBits",
  ]);
  const bits = await subtle().deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt: buf(salt), iterations },
    baseKey,
    dklen * 8,
  );
  return new Uint8Array(bits);
}

/** Derive the AES-256-GCM key used by every capture on this device. */
export async function deriveKey(
  passphrase: string,
  salt: Uint8Array,
  iterations: number = PBKDF2_ITERATIONS,
): Promise<CryptoKey> {
  const raw = await deriveKeyBytes(passphrase, salt, iterations, KEY_BYTES);
  return importAesKey(raw);
}

export async function importAesKey(raw: Uint8Array): Promise<CryptoKey> {
  if (!(raw instanceof Uint8Array) || raw.length !== KEY_BYTES) throw new CryptoError("invalid-key");
  return subtle().importKey("raw", buf(raw), { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
}

async function resolveKey(key: CryptoKey | Uint8Array): Promise<CryptoKey> {
  return key instanceof Uint8Array ? importAesKey(key) : key;
}

export interface EncryptOptions {
  /** Test/parity hook. Production always uses a random 12-byte IV. */
  iv?: Uint8Array;
}

/** Encrypt a payload. The result is the only thing that may leave the device. */
export async function encrypt(
  key: CryptoKey | Uint8Array,
  payload: CapturePayload,
  options: EncryptOptions = {},
): Promise<Envelope> {
  if (!isCapturePayload(payload)) throw new CryptoError("bad-payload");
  const iv = options.iv ?? generateIv();
  if (iv.length !== IV_BYTES) throw new CryptoError("invalid-envelope");

  const plaintext = utf8Encode(canonicalPayloadJson(payload));
  const ciphertext = new Uint8Array(
    await subtle().encrypt(
      { name: "AES-GCM", iv: buf(iv), tagLength: 128 },
      await resolveKey(key),
      buf(plaintext),
    ),
  );
  return {
    v: ENVELOPE_VERSION,
    iv: bytesToBase64(iv),
    ct: bytesToBase64(ciphertext),
  };
}

/** Decrypt an envelope. Throws CryptoError('decrypt-failed') on tamper or wrong key. */
export async function decrypt(
  key: CryptoKey | Uint8Array,
  envelope: Envelope | string,
): Promise<CapturePayload> {
  const parsed = typeof envelope === "string" ? parseEnvelope(envelope) : validateEnvelope(envelope);
  const iv = base64ToBytes(parsed.iv);
  const ciphertext = base64ToBytes(parsed.ct);
  if (iv.length !== IV_BYTES || ciphertext.length < GCM_TAG_BYTES) {
    throw new CryptoError("invalid-envelope");
  }

  let plaintext: Uint8Array;
  try {
    plaintext = new Uint8Array(
      await subtle().decrypt(
        { name: "AES-GCM", iv: buf(iv), tagLength: 128 },
        await resolveKey(key),
        buf(ciphertext),
      ),
    );
  } catch {
    throw new CryptoError("decrypt-failed");
  }

  let payload: unknown;
  try {
    payload = JSON.parse(utf8Decode(plaintext));
  } catch {
    throw new CryptoError("bad-payload");
  }
  if (!isCapturePayload(payload)) throw new CryptoError("bad-payload");
  return payload;
}

/** Canonical envelope JSON: `{"v":1,"iv":"...","ct":"..."}` — no whitespace, fixed order. */
export function envelopeToJson(envelope: Envelope): string {
  const valid = validateEnvelope(envelope);
  return `{"v":${valid.v},"iv":${JSON.stringify(valid.iv)},"ct":${JSON.stringify(valid.ct)}}`;
}

export function parseEnvelope(text: string): Envelope {
  if (typeof text !== "string") throw new CryptoError("invalid-envelope");
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    throw new CryptoError("invalid-envelope");
  }
  return validateEnvelope(value);
}

function validateEnvelope(value: unknown): Envelope {
  if (typeof value !== "object" || value === null) throw new CryptoError("invalid-envelope");
  const candidate = value as Record<string, unknown>;
  if (candidate.v !== ENVELOPE_VERSION) throw new CryptoError("invalid-envelope");
  if (typeof candidate.iv !== "string" || typeof candidate.ct !== "string") {
    throw new CryptoError("invalid-envelope");
  }
  try {
    if (base64ToBytes(candidate.iv).length !== IV_BYTES) throw new Error("bad-iv");
    if (base64ToBytes(candidate.ct).length < GCM_TAG_BYTES) throw new Error("bad-ct");
  } catch {
    throw new CryptoError("invalid-envelope");
  }
  return { v: 1, iv: candidate.iv, ct: candidate.ct };
}

/**
 * Encrypt raw bytes (used for the local SQLite database at rest). The output is
 * ciphertext || tag, with a fresh 12-byte IV unless one is injected for tests.
 */
export async function sealBytes(
  key: CryptoKey | Uint8Array,
  plaintext: Uint8Array,
  iv: Uint8Array = generateIv(),
): Promise<{ iv: Uint8Array; ct: Uint8Array }> {
  if (iv.length !== IV_BYTES) throw new CryptoError("invalid-envelope");
  const ct = new Uint8Array(
    await subtle().encrypt(
      { name: "AES-GCM", iv: buf(iv), tagLength: 128 },
      await resolveKey(key),
      buf(plaintext),
    ),
  );
  return { iv, ct };
}

/** Decrypt bytes produced by `sealBytes`. Throws CryptoError('decrypt-failed') on tamper. */
export async function openBytes(
  key: CryptoKey | Uint8Array,
  iv: Uint8Array,
  ct: Uint8Array,
): Promise<Uint8Array> {
  if (iv.length !== IV_BYTES || ct.length < GCM_TAG_BYTES) throw new CryptoError("invalid-envelope");
  try {
    return new Uint8Array(
      await subtle().decrypt(
        { name: "AES-GCM", iv: buf(iv), tagLength: 128 },
        await resolveKey(key),
        buf(ct),
      ),
    );
  } catch {
    throw new CryptoError("decrypt-failed");
  }
}

/**
 * Low-level AES-GCM seal returning ciphertext and tag separately.
 * Used by the NIST fixture test; production code uses `encrypt`.
 */
export async function aesGcmSealRaw(
  rawKey: Uint8Array,
  iv: Uint8Array,
  plaintext: Uint8Array,
  aad?: Uint8Array,
): Promise<{ ciphertext: Uint8Array; tag: Uint8Array }> {
  const params: AesGcmParams = { name: "AES-GCM", iv: buf(iv), tagLength: 128 };
  if (aad !== undefined && aad.length > 0) params.additionalData = buf(aad);
  const sealed = new Uint8Array(
    await subtle().encrypt(params, await importAesKey(rawKey), buf(plaintext)),
  );
  return {
    ciphertext: sealed.slice(0, sealed.length - GCM_TAG_BYTES),
    tag: sealed.slice(sealed.length - GCM_TAG_BYTES),
  };
}

/** Low-level AES-GCM open. Throws on tag mismatch. */
export async function aesGcmOpenRaw(
  rawKey: Uint8Array,
  iv: Uint8Array,
  ciphertext: Uint8Array,
  tag: Uint8Array,
  aad?: Uint8Array,
): Promise<Uint8Array> {
  const combined = new Uint8Array(ciphertext.length + tag.length);
  combined.set(ciphertext, 0);
  combined.set(tag, ciphertext.length);
  const params: AesGcmParams = { name: "AES-GCM", iv: buf(iv), tagLength: 128 };
  if (aad !== undefined && aad.length > 0) params.additionalData = buf(aad);
  return new Uint8Array(await subtle().decrypt(params, await importAesKey(rawKey), buf(combined)));
}

/**
 * Stable fingerprint of the derived key. Clients store this (not the passphrase) to verify
 * an unlock attempt on later launches.
 */
export async function keyFingerprint(rawKey: Uint8Array): Promise<string> {
  if (!(rawKey instanceof Uint8Array) || rawKey.length !== KEY_BYTES) {
    throw new CryptoError("invalid-key");
  }
  const prefix = utf8Encode("recall-verifier-v1:");
  const material = new Uint8Array(prefix.length + rawKey.length);
  material.set(prefix, 0);
  material.set(rawKey, prefix.length);
  const digest = new Uint8Array(await subtle().digest("SHA-256", buf(material)));
  return bytesToHex(digest).slice(0, 32);
}

/**
 * Sync identity handle: `u_` + PBKDF2(passphrase, "recall-sync-v1", 600k, 16 bytes) in hex.
 *
 * The same passphrase on a second device derives the same handle, so the relay returns the
 * same user_id + salt with no pairing ceremony. PBKDF2 (not a plain hash) keeps the handle
 * from becoming a fast offline passphrase verifier if the relay's user table leaks.
 */
export const SYNC_IDENTITY_SALT = "recall-sync-v1";

export async function deriveSyncUsername(passphrase: string): Promise<string> {
  const digest = await deriveKeyBytes(
    passphrase,
    utf8Encode(SYNC_IDENTITY_SALT),
    PBKDF2_ITERATIONS,
    16,
  );
  return `u_${bytesToHex(digest)}`;
}
