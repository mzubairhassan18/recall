import { describe, expect, it } from "vitest";
import type { AuthRegisterResponse, Envelope, SyncItem } from "@recall/shared-types";
import {
  bytesToBase64,
  decrypt,
  deriveKeyBytes,
  importAesKey,
  openBytes,
  sealBytes,
  utf8Encode,
} from "@recall/crypto-core";
import { CaptureStore, MemoryPersistence } from "../src/lib/db.js";
import { EncryptedPersistence } from "../src/lib/encrypted-persistence.js";
import { syncNow, type SyncConfig } from "../src/lib/sync.js";
import type { RelayApi } from "../src/lib/relay-client.js";

const SALT = utf8Encode("recall-salt-0016");

async function testKey(passphrase = "correct horse battery staple") {
  const bytes = await deriveKeyBytes(passphrase, SALT, 1, 32);
  return { bytes, key: await importAesKey(bytes) };
}

class FakeRelay implements RelayApi {
  readonly blobs = new Map<string, { envelope: Envelope; createdAt: number }>();
  listCalls: number[] = [];
  getCalls = 0;

  async register(username: string): Promise<AuthRegisterResponse> {
    return { user_id: `user-${username}`, salt: bytesToBase64(SALT) };
  }

  async putBlob(id: string, envelope: Envelope, createdAt: number): Promise<void> {
    this.blobs.set(id, { envelope, createdAt });
  }

  async getBlob(id: string): Promise<Envelope> {
    this.getCalls += 1;
    const entry = this.blobs.get(id);
    if (!entry) throw new Error("not-found");
    return entry.envelope;
  }

  async list(since: number): Promise<SyncItem[]> {
    this.listCalls.push(since);
    return [...this.blobs.entries()]
      .map(([id, entry]) => ({ id, created_at: entry.createdAt, size: JSON.stringify(entry.envelope).length }))
      .filter((item) => item.created_at > since)
      .sort((a, b) => a.created_at - b.created_at);
  }
}

function config(overrides: Partial<SyncConfig> = {}): SyncConfig {
  return {
    relayUrl: "https://relay.example",
    relaySecret: "test-secret",
    userId: "user-1",
    syncCursor: 0,
    localOnly: false,
    ...overrides,
  };
}

describe("local database encryption at rest", () => {
  it("never writes SQLite bytes in plaintext", async () => {
    const { key } = await testKey();
    const inner = new MemoryPersistence();
    const encrypted = new EncryptedPersistence(inner, key);

    const plaintext = utf8Encode("SQLite format 3\u0000very secret rows");
    await encrypted.save(plaintext);

    const raw = await inner.load();
    expect(raw).not.toBeNull();
    expect(new TextDecoder().decode(raw!)).not.toContain("very secret rows");
    expect(await encrypted.load()).toEqual(plaintext);

    const other = await testKey("a different passphrase");
    const wrong = new EncryptedPersistence(inner, other.key);
    await expect(wrong.load()).rejects.toMatchObject({ code: "decrypt-failed" });
  });

  it("seals arbitrary bytes with a fresh IV", async () => {
    const { key } = await testKey();
    const a = await sealBytes(key, utf8Encode("x"));
    const b = await sealBytes(key, utf8Encode("x"));
    expect(bytesToBase64(a.iv)).not.toBe(bytesToBase64(b.iv));
    expect(new TextDecoder().decode(await openBytes(key, b.iv, b.ct))).toBe("x");
  });
});

describe("sync", () => {
  it("uploads local captures as encrypted envelopes and marks them synced", async () => {
    const { key } = await testKey();
    const store = await CaptureStore.open(new MemoryPersistence());
    const relay = new FakeRelay();
    const id = "11111111-1111-4111-8111-111111111111";
    await store.insertCapture({
      id,
      created_at: 1_700_000_000_000,
      type: "text",
      text: "distributed locks and fencing tokens",
      synced: 0,
    });

    const report = await syncNow({ store, key, client: relay, config: config() });
    expect(report).toMatchObject({ pulled: 0, uploaded: 1, failed: 0, localOnly: false });
    expect(store.getCapture(id)?.synced).toBe(1);

    const uploaded = relay.blobs.get(id);
    expect(uploaded).toBeDefined();
    const payload = await decrypt(key, uploaded!.envelope);
    expect(payload).toMatchObject({ type: "text", text: "distributed locks and fencing tokens" });

    const again = await syncNow({ store, key, client: relay, config: config({ syncCursor: report.cursor }) });
    expect(again).toMatchObject({ pulled: 0, uploaded: 0, failed: 0 });
    store.close();
  });

  it("pulls and decrypts on a second device", async () => {
    const { key } = await testKey();
    const source = await CaptureStore.open(new MemoryPersistence());
    const target = await CaptureStore.open(new MemoryPersistence());
    const relay = new FakeRelay();
    const id = "22222222-2222-4222-8222-222222222222";
    await source.insertCapture({
      id,
      created_at: 1_700_000_100_000,
      type: "url",
      url: "https://engineering.example.com/distributed-locks",
      title: "Distributed locks",
      synced: 0,
    });
    await syncNow({ store: source, key, client: relay, config: config() });

    const report = await syncNow({ store: target, key, client: relay, config: config() });
    expect(report).toMatchObject({ pulled: 1, uploaded: 0, failed: 0 });
    expect(report.cursor).toBe(1_700_000_100_000);
    const row = target.getCapture(id);
    expect(row).toMatchObject({
      type: "url",
      url: "https://engineering.example.com/distributed-locks",
      title: "Distributed locks",
      synced: 1,
    });

    const results = await target.search("distributed locks", { lookup: new Map(), size: 0 });
    expect(results[0]?.id).toBe(id);
    source.close();
    target.close();
  });

  it("makes no network calls in LOCAL_ONLY mode", async () => {
    const { key } = await testKey();
    const store = await CaptureStore.open(new MemoryPersistence());
    const relay = new FakeRelay();
    await store.insertCapture({ id: "33333333-3333-4333-8333-333333333333", created_at: 1, type: "text", text: "x" });

    const report = await syncNow({ store, key, client: null, config: config({ localOnly: true }) });
    expect(report).toMatchObject({ pulled: 0, uploaded: 0, failed: 0, localOnly: true });
    expect(relay.listCalls).toHaveLength(0);
    expect(relay.blobs.size).toBe(0);
    store.close();
  });

  it("does not advance the cursor past an unreadable envelope", async () => {
    const { key } = await testKey();
    const wrong = await testKey("wrong passphrase");
    const source = await CaptureStore.open(new MemoryPersistence());
    const target = await CaptureStore.open(new MemoryPersistence());
    const relay = new FakeRelay();
    const id = "44444444-4444-4444-8444-444444444444";
    await source.insertCapture({ id, created_at: 5_000, type: "text", text: "secret" });
    await syncNow({ store: source, key, client: relay, config: config() });

    const report = await syncNow({ store: target, key: wrong.key, client: relay, config: config() });
    expect(report.failed).toBe(1);
    expect(report.cursor).toBe(0);
    expect(target.hasCapture(id)).toBe(false);

    const recovered = await syncNow({ store: target, key, client: relay, config: config() });
    expect(recovered.pulled).toBe(1);
    expect(target.hasCapture(id)).toBe(true);
    source.close();
    target.close();
  });
});
