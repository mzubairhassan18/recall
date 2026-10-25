import type { AuthRegisterResponse } from "@recall/shared-types";
import {
  base64ToBytes,
  bytesToBase64,
  deriveKeyBytes,
  deriveSyncUsername,
  importAesKey,
  keyFingerprint,
  generateSalt,
} from "@recall/crypto-core";

export { deriveSyncUsername };

export interface Identity {
  username: string;
  userId: string;
  saltB64: string;
  keyBytes: Uint8Array;
  key: CryptoKey;
  fingerprint: string;
}

export type RegisterFn = (username: string) => Promise<AuthRegisterResponse>;

/** Create an identity. When `register` is null the client is LOCAL_ONLY and generates its own salt. */
export async function createIdentity(
  passphrase: string,
  register: RegisterFn | null,
): Promise<Identity> {
  const username = await deriveSyncUsername(passphrase);
  let userId: string;
  let saltB64: string;

  if (register) {
    const registered = await register(username);
    userId = registered.user_id;
    saltB64 = registered.salt;
  } else {
    userId = crypto.randomUUID();
    saltB64 = bytesToBase64(generateSalt());
  }

  const salt = base64ToBytes(saltB64);
  if (salt.length !== 16) throw new Error("invalid-salt");
  const keyBytes = await deriveKeyBytes(passphrase, salt);
  const key = await importAesKey(keyBytes);
  return {
    username,
    userId,
    saltB64,
    keyBytes,
    key,
    fingerprint: await keyFingerprint(keyBytes),
  };
}

/** Re-derive and verify an unlock attempt. Never store the passphrase. */
export async function unlockIdentity(
  passphrase: string,
  saltB64: string,
  expectedFingerprint: string,
): Promise<{ keyBytes: Uint8Array; key: CryptoKey; fingerprint: string }> {
  const salt = base64ToBytes(saltB64);
  if (salt.length !== 16) throw new Error("invalid-salt");
  const keyBytes = await deriveKeyBytes(passphrase, salt);
  const fingerprint = await keyFingerprint(keyBytes);
  if (fingerprint !== expectedFingerprint) throw new Error("wrong-passphrase");
  return { keyBytes, key: await importAesKey(keyBytes), fingerprint };
}
