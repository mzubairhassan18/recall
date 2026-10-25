import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import {
  aesGcmOpenRaw,
  aesGcmSealRaw,
  bytesToHex,
  hexToBytes,
} from "../src/index.js";

interface AesFixture {
  vectors: Array<{
    name: string;
    key_hex: string;
    iv_hex: string;
    plaintext_hex: string;
    aad_hex: string;
    ciphertext_hex: string;
    tag_hex: string;
  }>;
}

const fixture = JSON.parse(
  readFileSync(
    fileURLToPath(new URL("../../../docs/test-vectors/aesgcm.json", import.meta.url)),
    "utf8",
  ),
) as AesFixture;

describe("AES-256-GCM", () => {
  for (const vector of fixture.vectors) {
    it(`seals ${vector.name}`, async () => {
      const sealed = await aesGcmSealRaw(
        hexToBytes(vector.key_hex),
        hexToBytes(vector.iv_hex),
        hexToBytes(vector.plaintext_hex),
        hexToBytes(vector.aad_hex),
      );
      expect(bytesToHex(sealed.ciphertext)).toBe(vector.ciphertext_hex);
      expect(bytesToHex(sealed.tag)).toBe(vector.tag_hex);
    });

    it(`opens ${vector.name}`, async () => {
      const opened = await aesGcmOpenRaw(
        hexToBytes(vector.key_hex),
        hexToBytes(vector.iv_hex),
        hexToBytes(vector.ciphertext_hex),
        hexToBytes(vector.tag_hex),
        hexToBytes(vector.aad_hex),
      );
      expect(bytesToHex(opened)).toBe(vector.plaintext_hex);
    });

    it(`rejects a tampered tag for ${vector.name}`, async () => {
      const tag = hexToBytes(vector.tag_hex);
      tag[0] = (tag[0] as number) ^ 0xff;
      await expect(
        aesGcmOpenRaw(
          hexToBytes(vector.key_hex),
          hexToBytes(vector.iv_hex),
          hexToBytes(vector.ciphertext_hex),
          tag,
          hexToBytes(vector.aad_hex),
        ),
      ).rejects.toThrow();
    });
  }
});
