import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { bytesToHex, deriveKeyBytes, deriveSyncUsername, PBKDF2_ITERATIONS, utf8Encode } from "../src/index.js";

interface Pbkdf2Fixture {
  vectors: Array<{
    name: string;
    passphrase: string;
    salt_utf8: string;
    iterations: number;
    dklen: number;
    dk_hex: string;
  }>;
}

const fixture = JSON.parse(
  readFileSync(
    fileURLToPath(new URL("../../../docs/test-vectors/pbkdf2.json", import.meta.url)),
    "utf8",
  ),
) as Pbkdf2Fixture;

describe("PBKDF2-HMAC-SHA256", () => {
  it("uses the production cost from the wire format", () => {
    expect(PBKDF2_ITERATIONS).toBe(600_000);
  });

  for (const vector of fixture.vectors) {
    it(`matches ${vector.name}`, async () => {
      const derived = await deriveKeyBytes(
        vector.passphrase,
        utf8Encode(vector.salt_utf8),
        vector.iterations,
        vector.dklen,
      );
      expect(derived).toHaveLength(vector.dklen);
      expect(bytesToHex(derived)).toBe(vector.dk_hex);
    }, 120_000);
  }

  it("rejects an empty passphrase and empty salt", async () => {
    await expect(deriveKeyBytes("", utf8Encode("salt"))).rejects.toMatchObject({
      code: "invalid-passphrase",
    });
    await expect(deriveKeyBytes("x", new Uint8Array(0))).rejects.toMatchObject({
      code: "invalid-salt",
    });
  });

  it("derives a stable sync identity handle", async () => {
    const a = await deriveSyncUsername("correct horse battery staple");
    const b = await deriveSyncUsername("correct horse battery staple");
    const c = await deriveSyncUsername("correct horse battery stapl3");
    expect(a).toBe(b);
    expect(a).toMatch(/^u_[0-9a-f]{32}$/);
    expect(c).not.toBe(a);
  }, 120_000);
});
