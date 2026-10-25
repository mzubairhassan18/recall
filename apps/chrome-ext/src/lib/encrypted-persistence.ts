import { openBytes, sealBytes } from "@recall/crypto-core";
import type { SqlitePersistence } from "./db.js";

/**
 * Wraps a byte persistence with AES-256-GCM. The SQLite file never touches disk/IndexedDB
 * in plaintext: the stored value is `iv(12) || ciphertext || tag(16)`.
 */
export class EncryptedPersistence implements SqlitePersistence {
  private readonly inner: SqlitePersistence;
  private readonly key: CryptoKey;

  constructor(inner: SqlitePersistence, key: CryptoKey) {
    this.inner = inner;
    this.key = key;
  }

  async load(): Promise<Uint8Array | null> {
    const stored = await this.inner.load();
    if (!stored || stored.length === 0) return null;
    if (stored.length < 12 + 16) throw new Error("corrupt-database");
    return openBytes(this.key, stored.slice(0, 12), stored.slice(12));
  }

  async save(bytes: Uint8Array): Promise<void> {
    const sealed = await sealBytes(this.key, bytes);
    const combined = new Uint8Array(sealed.iv.length + sealed.ct.length);
    combined.set(sealed.iv, 0);
    combined.set(sealed.ct, sealed.iv.length);
    await this.inner.save(combined);
  }
}
