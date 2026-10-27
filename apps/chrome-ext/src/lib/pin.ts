import {
  base64ToBytes,
  bytesToBase64,
  CryptoError,
  deriveKeyBytes,
  generateSalt,
  KEY_BYTES,
  openBytes,
  PBKDF2_ITERATIONS,
  SALT_BYTES,
  sealBytes,
} from "@recall/crypto-core";

export const PIN_LENGTH = 4;
export const PIN_MAX_ATTEMPTS = 5;
export const PIN_BASE_LOCKOUT_MS = 30_000;
export const PIN_MAX_LOCKOUT_MS = 15 * 60_000;

export interface PinWrap {
  salt: string;
  iv: string;
  ct: string;
}

export function isValidPin(pin: unknown): pin is string {
  return typeof pin === "string" && new RegExp(`^\\d{${PIN_LENGTH}}$`).test(pin);
}

export function assertPin(pin: unknown): asserts pin is string {
  if (!isValidPin(pin)) throw new Error("invalid-pin");
}

/** Consecutive failures below the threshold do not lock the vault. */
export function pinLockoutMs(failures: number): number {
  if (!Number.isFinite(failures) || failures < PIN_MAX_ATTEMPTS) return 0;
  const step = Math.min(Math.floor(failures) - PIN_MAX_ATTEMPTS, 5);
  return Math.min(PIN_BASE_LOCKOUT_MS * 2 ** step, PIN_MAX_LOCKOUT_MS);
}

/**
 * Wrap the 32-byte vault key with a key derived from the local PIN.
 *
 * The PIN is never stored. Only the random salt and AES-GCM output are written to
 * `chrome.storage.local`. A wrong PIN fails the GCM tag check before any data key is used.
 */
export async function createPinWrap(
  pin: string,
  keyBytes: Uint8Array,
  iterations = PBKDF2_ITERATIONS,
): Promise<PinWrap> {
  assertPin(pin);
  if (!(keyBytes instanceof Uint8Array) || keyBytes.length !== KEY_BYTES) {
    throw new Error("invalid-key");
  }

  const salt = generateSalt();
  const pinKey = await deriveKeyBytes(pin, salt, iterations, KEY_BYTES);
  try {
    const sealed = await sealBytes(pinKey, keyBytes);
    return {
      salt: bytesToBase64(salt),
      iv: bytesToBase64(sealed.iv),
      ct: bytesToBase64(sealed.ct),
    };
  } finally {
    pinKey.fill(0);
  }
}

/** Unwrap the vault key. Throws `wrong-pin` on a bad PIN or tampered wrap. */
export async function openPinWrap(
  pin: string,
  wrap: PinWrap,
  iterations = PBKDF2_ITERATIONS,
): Promise<Uint8Array> {
  assertPin(pin);
  let salt: Uint8Array;
  let iv: Uint8Array;
  let ct: Uint8Array;
  try {
    salt = base64ToBytes(wrap.salt);
    iv = base64ToBytes(wrap.iv);
    ct = base64ToBytes(wrap.ct);
  } catch {
    throw new Error("invalid-pin-wrap");
  }
  if (salt.length !== SALT_BYTES) throw new Error("invalid-pin-wrap");

  const pinKey = await deriveKeyBytes(pin, salt, iterations, KEY_BYTES);
  try {
    const keyBytes = await openBytes(pinKey, iv, ct);
    if (keyBytes.length !== KEY_BYTES) throw new Error("invalid-pin-wrap");
    return keyBytes;
  } catch (error) {
    if (error instanceof CryptoError && error.code === "decrypt-failed") {
      throw new Error("wrong-pin");
    }
    throw error;
  } finally {
    pinKey.fill(0);
  }
}
