import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import type { CapturePayload, Envelope } from "@recall/shared-types";
import {
  base64ToBytes,
  canonicalPayloadJson,
  decrypt,
  encrypt,
  envelopeToJson,
  hexToBytes,
  importAesKey,
  keyFingerprint,
  openBytes,
  parseEnvelope,
  sealBytes,
  utf8Encode,
} from "../src/index.js";

interface RoundTripFixture {
  key_hex: string;
  vectors: Array<{ name: string; iv_hex: string; payload: CapturePayload }>;
}

const fixture = JSON.parse(
  readFileSync(
    fileURLToPath(new URL("../../../docs/test-vectors/envelope-roundtrip.json", import.meta.url)),
    "utf8",
  ),
) as RoundTripFixture;

describe("envelope round-trip", () => {
  const keyBytes = hexToBytes(fixture.key_hex);

  for (const vector of fixture.vectors) {
    it(`round-trips ${vector.name} byte-identically`, async () => {
      const key = await importAesKey(keyBytes);
      const envelope = await encrypt(key, vector.payload, { iv: hexToBytes(vector.iv_hex) });
      expect(envelope.v).toBe(1);
      expect(base64ToBytes(envelope.iv)).toHaveLength(12);
      expect(base64ToBytes(envelope.ct).length).toBeGreaterThanOrEqual(16);

      const decrypted = await decrypt(key, envelope);
      expect(decrypted).toEqual(vector.payload);
      expect(canonicalPayloadJson(decrypted)).toBe(canonicalPayloadJson(vector.payload));
    });
  }

  it("produces canonical envelope JSON", async () => {
    const key = await importAesKey(keyBytes);
    const envelope = await encrypt(
      key,
      { type: "text", text: "canonical" },
      { iv: new Uint8Array(12).fill(7) },
    );
    const json = envelopeToJson(envelope);
    expect(json).toMatch(/^\{"v":1,"iv":"[^"]+","ct":"[^"]+"\}$/);
    expect(parseEnvelope(json)).toEqual(envelope);
  });

  it("rejects ciphertext tampering", async () => {
    const key = await importAesKey(keyBytes);
    const envelope = await encrypt(key, { type: "text", text: "tamper me" });
    const ct = base64ToBytes(envelope.ct);
    ct[0] = (ct[0] as number) ^ 0x01;
    const tampered: Envelope = { ...envelope, ct: Buffer.from(ct).toString("base64") };
    await expect(decrypt(key, tampered)).rejects.toMatchObject({ code: "decrypt-failed" });
  });

  it("rejects the wrong key", async () => {
    const envelope = await encrypt(keyBytes, { type: "text", text: "wrong key" });
    const other = new Uint8Array(keyBytes);
    other[0] = (other[0] as number) ^ 0xff;
    await expect(decrypt(other, envelope)).rejects.toMatchObject({ code: "decrypt-failed" });
  });

  it("rejects malformed envelopes and payloads", async () => {
    const key = await importAesKey(keyBytes);
    await expect(decrypt(key, '{bad json')).rejects.toMatchObject({ code: "invalid-envelope" });
    await expect(decrypt(key, { v: 2, iv: "AAAAAAAAAAAAAAAA", ct: "AAAAAAAAAAAAAAAAAAAAAA==" } as unknown as Envelope)).rejects.toMatchObject({
      code: "invalid-envelope",
    });
    await expect(encrypt(key, { type: "url" })).rejects.toMatchObject({ code: "bad-payload" });
    await expect(
      encrypt(key, { type: "text", text: "x" }, { iv: new Uint8Array(8) }),
    ).rejects.toMatchObject({ code: "invalid-envelope" });
  });

  it("seals and opens arbitrary bytes (local DB at rest)", async () => {
    const key = await importAesKey(keyBytes);
    const plaintext = utf8Encode("SQLite format 3\u0000encrypted at rest");
    const sealed = await sealBytes(key, plaintext);
    expect(sealed.iv).toHaveLength(12);
    expect(sealed.ct.length).toBe(plaintext.length + 16);
    expect(new TextDecoder().decode(await openBytes(key, sealed.iv, sealed.ct))).toBe(
      "SQLite format 3\u0000encrypted at rest",
    );
    const tampered = new Uint8Array(sealed.ct);
    tampered[0] = (tampered[0] as number) ^ 0xff;
    await expect(openBytes(key, sealed.iv, tampered)).rejects.toMatchObject({ code: "decrypt-failed" });
  });

  it("fingerprints the derived key deterministically", async () => {
    const a = await keyFingerprint(keyBytes);
    const b = await keyFingerprint(new Uint8Array(keyBytes));
    expect(a).toBe(b);
    expect(a).toMatch(/^[0-9a-f]{32}$/);
    const other = new Uint8Array(keyBytes);
    other[31] = (other[31] as number) ^ 0xff;
    expect(await keyFingerprint(other)).not.toBe(a);
  });
});
