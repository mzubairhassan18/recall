import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { deriveKeyBytes, importAesKey, utf8Encode } from "@recall/crypto-core";
import { CaptureStore } from "../src/lib/db.js";
import { EncryptedPersistence } from "../src/lib/encrypted-persistence.js";
import { FilePersistence } from "../src/lib/file-persistence.js";
import { EMPTY_CONFIG, loadConfig, saveConfig, type SecretBox } from "../src/lib/config.js";

const directories: string[] = [];

async function temporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), "recall-desktop-"));
  directories.push(directory);
  return directory;
}

afterEach(async () => {
  await Promise.all(directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

const fakeBox: SecretBox = {
  available: true,
  encrypt: async (plaintext) => `enc:${Buffer.from(plaintext, "utf8").toString("base64")}`,
  decrypt: async (token) => Buffer.from(token.replace(/^enc:/, ""), "base64").toString("utf8"),
};

describe("desktop settings", () => {
  it("round-trips settings and never writes the relay secret in plaintext", async () => {
    const directory = await temporaryDirectory();
    const file = join(directory, "config.json");
    await saveConfig(
      file,
      {
        ...EMPTY_CONFIG,
        relayUrl: "https://relay.example",
        relaySecret: "super-secret-value",
        username: "u_abc",
        userId: "user-1",
        salt: "c2FsdHNhbHRzYWx0c2FsdA==",
        localOnly: false,
        fingerprint: "fingerprint",
      },
      fakeBox,
    );

    const raw = await readFile(file, "utf8");
    expect(raw).not.toContain("super-secret-value");

    const loaded = await loadConfig(file, fakeBox);
    expect(loaded).toMatchObject({
      relayUrl: "https://relay.example",
      relaySecret: "super-secret-value",
      username: "u_abc",
      userId: "user-1",
      localOnly: false,
      fingerprint: "fingerprint",
    });
  });

  it("falls back to base64 when safeStorage is unavailable", async () => {
    const directory = await temporaryDirectory();
    const file = join(directory, "config.json");
    const box: SecretBox = {
      available: false,
      encrypt: async () => {
        throw new Error("not available");
      },
      decrypt: async () => {
        throw new Error("not available");
      },
    };
    await saveConfig(file, { ...EMPTY_CONFIG, relaySecret: "fallback-secret" }, box);
    const raw = await readFile(file, "utf8");
    expect(raw).not.toContain("fallback-secret");
    expect((await loadConfig(file, box)).relaySecret).toBe("fallback-secret");
  });

  it("returns defaults for a missing or corrupt config", async () => {
    const directory = await temporaryDirectory();
    expect(await loadConfig(join(directory, "missing.json"), fakeBox)).toMatchObject(EMPTY_CONFIG);

    const file = join(directory, "config.json");
    await saveConfig(file, { ...EMPTY_CONFIG }, fakeBox);
    expect((await loadConfig(file, fakeBox)).fingerprint).toBeNull();
  });
});

describe("desktop database file", () => {
  it("stores SQLite bytes encrypted and reopens with the same passphrase", async () => {
    const directory = await temporaryDirectory();
    const file = join(directory, "recall.db.enc");
    const keyBytes = await deriveKeyBytes("correct horse battery staple", utf8Encode("recall-salt-0016"), 1, 32);
    const key = await importAesKey(keyBytes);
    const persistence = new EncryptedPersistence(new FilePersistence(file), key);

    const store = await CaptureStore.open(persistence);
    await store.insertCapture({
      id: "11111111-1111-4111-8111-111111111111",
      created_at: 1_700_000_000_000,
      type: "text",
      text: "plaintext that must not leak to disk",
      synced: 0,
    });
    store.close();

    const raw = await readFile(file);
    expect(raw.includes(Buffer.from("SQLite format 3", "utf8"))).toBe(false);
    expect(raw.includes(Buffer.from("plaintext that must not leak", "utf8"))).toBe(false);

    const reopened = await CaptureStore.open(persistence);
    expect(reopened.count()).toBe(1);
    reopened.close();

    const wrong = await importAesKey(
      await deriveKeyBytes("a different passphrase", utf8Encode("recall-salt-0016"), 1, 32),
    );
    const failing = new EncryptedPersistence(new FilePersistence(file), wrong);
    await expect(CaptureStore.open(failing)).rejects.toMatchObject({ code: "decrypt-failed" });
  });
});
